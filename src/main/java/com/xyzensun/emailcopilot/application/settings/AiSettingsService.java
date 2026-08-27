package com.xyzensun.emailcopilot.application.settings;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xyzensun.emailcopilot.application.settings.model.SystemSettingsView;
import com.xyzensun.emailcopilot.domain.enums.AiProvider;
import com.xyzensun.emailcopilot.domain.enums.SecretType;
import com.xyzensun.emailcopilot.infrastructure.ai.AiConnectionTester;
import com.xyzensun.emailcopilot.infrastructure.ai.AiModelConstructionException;
import com.xyzensun.emailcopilot.infrastructure.ai.AiNotConfiguredException;
import com.xyzensun.emailcopilot.infrastructure.ai.AiRuntimeSettings;
import com.xyzensun.emailcopilot.infrastructure.ai.AiTestResult;
import com.xyzensun.emailcopilot.infrastructure.ai.ChatModelHolder;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.AppSetting;
import com.xyzensun.emailcopilot.infrastructure.persistence.mapper.AppSettingMapper;
import com.xyzensun.emailcopilot.infrastructure.security.ExternalAccountSecretStore;
import com.xyzensun.emailcopilot.infrastructure.security.MasterKeyStatus;
import com.xyzensun.emailcopilot.infrastructure.security.MasterKeyStatusSource;
import com.xyzensun.emailcopilot.interfaces.error.ApiError;
import com.xyzensun.emailcopilot.interfaces.error.ApiException;
import com.xyzensun.emailcopilot.interfaces.error.ValidationErrorItem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AI system settings 的独立应用服务。
 *
 * <p>本地写事务提交后才重建运行时模型。若 SDK 构造仍失败，数据库保留用户刚提交的配置并返回
 * {@code AI_SETTINGS_INVALID}，holder 则继续指向旧的可用模型；这使用户能看到并修正事实源中的
 * 新值，同时不会让一次失败重载破坏正在工作的旧调用。型号/key 被清空则提交后主动清空 holder。
 *
 * <p>更新只 SET PATCH 中出现的列，避免与其它设置请求并发时用旧整行快照覆盖对方。进程内的 AI
 * 更新与重载串行化，防止两个已提交请求反序重载而让 holder 最终落后于数据库。
 */
@Service
public class AiSettingsService {

    private static final short SETTINGS_ROW_ID = 1;
    private static final Set<String> AI_FIELDS = Set.of(
            "aiProvider", "aiBaseUrl", "aiModel", "aiContextWindowK", "aiTimeoutSeconds");

    private final AppSettingMapper appSettingMapper;
    private final ExternalAccountSecretStore secretStore;
    private final ChatModelHolder chatModelHolder;
    private final AiConnectionTester aiConnectionTester;
    private final MasterKeyStatusSource masterKeyStatusSource;
    private final TransactionTemplate transactionTemplate;
    private final Object updateAndReloadMonitor = new Object();

    public AiSettingsService(
            AppSettingMapper appSettingMapper,
            ExternalAccountSecretStore secretStore,
            ChatModelHolder chatModelHolder,
            AiConnectionTester aiConnectionTester,
            MasterKeyStatusSource masterKeyStatusSource,
            PlatformTransactionManager transactionManager) {
        this.appSettingMapper = appSettingMapper;
        this.secretStore = secretStore;
        this.chatModelHolder = chatModelHolder;
        this.aiConnectionTester = aiConnectionTester;
        this.masterKeyStatusSource = masterKeyStatusSource;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SystemSettingsView getSystemSettings() {
        AppSetting setting = getRequiredSetting();
        AiRuntimeSettings runtimeSettings = toRuntimeSettings(setting);
        boolean keyConfigured = secretStore.exists(SecretType.AI_API_KEY, null);
        boolean mcpKeyConfigured = secretStore.exists(SecretType.EXA_API_KEY, null);
        boolean tavilyKeyConfigured = secretStore.exists(SecretType.TAVILY_API_KEY, null);
        MasterKeyStatus keyStatus = masterKeyStatusSource.getMasterKeyStatus();
        return new SystemSettingsView(
                runtimeSettings.provider(),
                runtimeSettings.baseUrl(),
                runtimeSettings.model(),
                runtimeSettings.contextWindowK(),
                runtimeSettings.timeoutSeconds(),
                keyConfigured,
                runtimeSettings.modelConfigured() && keyConfigured,
                keyStatus.masterKeyPresent(),
                keyStatus.masterKeyMatchesCiphertext(),
                mcpKeyConfigured,
                tavilyKeyConfigured);
    }

    public SystemSettingsView updateSystemSettings(AiSettingsPatch patch) {
        validatePresence(patch);
        synchronized (updateAndReloadMonitor) {
            AppSetting current = getRequiredSetting();
            validateMergedSettings(current, patch);
            persistPatch(patch);
            reloadCommittedRuntimeForRequest();
            return getSystemSettings();
        }
    }

    public void saveAiApiKey(String plaintextApiKey) {
        if (plaintextApiKey == null || plaintextApiKey.isBlank()) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("value", "AI API key 不能为空")));
        }
        synchronized (updateAndReloadMonitor) {
            transactionTemplate.executeWithoutResult(status ->
                    secretStore.save(SecretType.AI_API_KEY, null, plaintextApiKey));
            reloadCommittedRuntimeForRequest();
        }
    }

    /**
     * 写入或覆盖 Exa MCP API key（design.md §8.2）。
     *
     * <p>与 AI key 同为 {@code mail_account_id=null} 全局凭据，AES-GCM 加密存储。
     * 204 永不回显，不进日志。key 更新后立即生效（MCP 请求时实时从存储读取）。
     */
    public void saveMcpApiKey(String plaintextApiKey) {
        if (plaintextApiKey == null || plaintextApiKey.isBlank()) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("value", "Exa MCP API key 不能为空")));
        }
        synchronized (updateAndReloadMonitor) {
            transactionTemplate.executeWithoutResult(status ->
                    secretStore.save(SecretType.EXA_API_KEY, null, plaintextApiKey));
        }
    }

    /**
     * 写入或覆盖 Tavily API key（阶段10 prd A1）。
     *
     * <p>与 Exa key 同为 {@code mail_account_id=null} 全局凭据，AES-GCM 加密存储。
     * 204 永不回显，不进日志。本阶段只存 key 暂不接入对话工具链，后续阶段再决定接入。
     */
    public void saveTavilyApiKey(String plaintextApiKey) {
        if (plaintextApiKey == null || plaintextApiKey.isBlank()) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("value", "Tavily API key 不能为空")));
        }
        synchronized (updateAndReloadMonitor) {
            transactionTemplate.executeWithoutResult(status ->
                    secretStore.save(SecretType.TAVILY_API_KEY, null, plaintextApiKey));
        }
    }

    /** 远程 provider 调用不在数据库事务中；失败与超时由结果体表达。 */
    public AiTestResult testAiConnection() {
        try {
            return aiConnectionTester.testConnection();
        } catch (AiNotConfiguredException exception) {
            throw new ApiException(ApiError.AI_NOT_CONFIGURED);
        }
    }

    /** 启动恢复路径不转换异常：非法配置或密文解不开必须让容器 fail-fast。 */
    public void initializeRuntime() {
        synchronized (updateAndReloadMonitor) {
            AiRuntimeSettings settings = toRuntimeSettings(getRequiredSetting());
            String apiKey = secretStore.load(SecretType.AI_API_KEY, null).orElse(null);
            chatModelHolder.reload(settings, apiKey);
        }
    }

    private void persistPatch(AiSettingsPatch patch) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                LambdaUpdateWrapper<AppSetting> update = new LambdaUpdateWrapper<AppSetting>()
                        .eq(AppSetting::getId, SETTINGS_ROW_ID);
                if (patch.provided("aiProvider")) {
                    update.set(AppSetting::getAiProvider, parseProvider(patch.aiProvider()));
                }
                if (patch.provided("aiBaseUrl")) {
                    update.set(AppSetting::getAiBaseUrl, patch.aiBaseUrl());
                }
                if (patch.provided("aiModel")) {
                    update.set(AppSetting::getAiModel, patch.aiModel());
                }
                if (patch.provided("aiContextWindowK")) {
                    update.set(AppSetting::getAiContextWindowK, patch.aiContextWindowK());
                }
                if (patch.provided("aiTimeoutSeconds")) {
                    update.set(AppSetting::getAiTimeoutSeconds, patch.aiTimeoutSeconds());
                }
                // Wrapper update 不走实体字段的自动填充；显式使用数据库时间，确保审计列随 PATCH 更新。
                update.setSql("updated_at = now()");
                if (appSettingMapper.update(null, update) != 1) {
                    throw new IllegalStateException("应用配置更新未生效");
                }
            });
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ApiError.AI_SETTINGS_INVALID);
        }
    }

    private void reloadCommittedRuntimeForRequest() {
        AiRuntimeSettings settings = toRuntimeSettings(getRequiredSetting());
        String apiKey = secretStore.load(SecretType.AI_API_KEY, null).orElse(null);
        try {
            chatModelHolder.reload(settings, apiKey);
        } catch (AiModelConstructionException exception) {
            // 数据库事务已经提交；holder 的“成功后才赋值”保证旧引用仍可继续服务。
            throw new ApiException(ApiError.AI_SETTINGS_INVALID, "已保存配置，但无法构造 AI 客户端");
        }
    }

    private static void validatePresence(AiSettingsPatch patch) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (patch.providedFields().isEmpty()) {
            errors.add(new ValidationErrorItem("$", "至少需要提供一个 AI 连接参数"));
        }
        requireNonNullWhenProvided(patch, "aiProvider", patch.aiProvider(), errors);
        requireNonNullWhenProvided(patch, "aiContextWindowK", patch.aiContextWindowK(), errors);
        requireNonNullWhenProvided(patch, "aiTimeoutSeconds", patch.aiTimeoutSeconds(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    private static void validateMergedSettings(AppSetting current, AiSettingsPatch patch) {
        AiProvider provider = patch.provided("aiProvider")
                ? parseProvider(patch.aiProvider())
                : current.getAiProvider();
        String baseUrl = patch.provided("aiBaseUrl") ? patch.aiBaseUrl() : current.getAiBaseUrl();
        String model = patch.provided("aiModel") ? patch.aiModel() : current.getAiModel();
        int contextWindowK = patch.provided("aiContextWindowK")
                ? patch.aiContextWindowK()
                : current.getAiContextWindowK();
        int timeoutSeconds = patch.provided("aiTimeoutSeconds")
                ? patch.aiTimeoutSeconds()
                : current.getAiTimeoutSeconds();
        try {
            new AiRuntimeSettings(provider, baseUrl, model, contextWindowK, timeoutSeconds);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ApiError.AI_SETTINGS_INVALID, exception.getMessage());
        }
    }

    private static AiProvider parseProvider(String value) {
        if (value == null) {
            throw ApiException.validationFailed(
                    List.of(new ValidationErrorItem("aiProvider", "值不能为 null")));
        }
        return switch (value) {
            case "openai" -> AiProvider.OPENAI;
            case "anthropic" -> AiProvider.ANTHROPIC;
            default -> throw new ApiException(
                    ApiError.AI_SETTINGS_INVALID, "aiProvider 只支持 openai 或 anthropic");
        };
    }

    private AiRuntimeSettings toRuntimeSettings(AppSetting setting) {
        return new AiRuntimeSettings(
                setting.getAiProvider(),
                setting.getAiBaseUrl(),
                setting.getAiModel(),
                setting.getAiContextWindowK(),
                setting.getAiTimeoutSeconds());
    }

    private AppSetting getRequiredSetting() {
        AppSetting setting = appSettingMapper.selectById(SETTINGS_ROW_ID);
        if (setting == null) {
            throw new IllegalStateException("应用配置行不存在");
        }
        return setting;
    }

    private static void requireNonNullWhenProvided(
            AiSettingsPatch patch,
            String field,
            Object value,
            List<ValidationErrorItem> errors) {
        if (patch.provided(field) && value == null) {
            errors.add(new ValidationErrorItem(field, "值不能为 null"));
        }
    }

    /** presence set 是 PATCH 语义的一部分；baseUrl/model 的 present+null 表示主动清空。 */
    public record AiSettingsPatch(
            String aiProvider,
            String aiBaseUrl,
            String aiModel,
            Integer aiContextWindowK,
            Integer aiTimeoutSeconds,
            Set<String> providedFields) {

        public AiSettingsPatch {
            providedFields = Set.copyOf(providedFields);
            if (!AI_FIELDS.containsAll(providedFields)) {
                throw new IllegalArgumentException("AI settings patch 包含未知字段");
            }
        }

        public boolean provided(String field) {
            return providedFields.contains(field);
        }
    }
}
