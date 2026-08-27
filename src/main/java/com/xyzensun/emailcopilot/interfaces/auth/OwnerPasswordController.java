package com.xyzensun.emailcopilot.interfaces.auth;

import com.xyzensun.emailcopilot.application.auth.AuthApplicationService;
import com.xyzensun.emailcopilot.interfaces.auth.dto.PasswordChangeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 修改登录口令（{@code API.md} §7）。
 *
 * <p>成功后<b>全部 session 失效（含当前）</b>，前端直接跳登录页——
 * 改密的动机往往正是怀疑口令泄露，保留当前 session 等于给可能已在里面的人留门。
 */
@RestController
@RequestMapping("/api/owner/password")
public class OwnerPasswordController {

    private final AuthApplicationService authApplicationService;

    public OwnerPasswordController(AuthApplicationService authApplicationService) {
        this.authApplicationService = authApplicationService;
    }

    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordChangeRequest passwordChangeRequest,
                               @AuthenticationPrincipal UserDetails owner) {
        authApplicationService.changePassword(
                owner.getUsername(), passwordChangeRequest.currentPassword(), passwordChangeRequest.newPassword());
    }
}
