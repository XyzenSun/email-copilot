package com.xyzensun.emailcopilot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xyzensun.emailcopilot.infrastructure.persistence.entity.OwnerCredential;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 登录凭据，加盐哈希不可逆。
 */
public interface OwnerCredentialMapper extends BaseMapper<OwnerCredential> {

    /**
     * 只有哈希仍是调用方刚刚验证过的旧值时才更新，避免两个改密请求互相覆盖。
     * 受影响行数为零表示另一个请求已经先改了口令，应用层把它映射为当前口令失效。
     */
    @Update("""
            update owner_credential
               set password_hash = #{newPasswordHash}, updated_at = now()
             where username = #{username}
               and password_hash = #{expectedPasswordHash}
            """)
    int updatePasswordIfUnchanged(@Param("username") String username,
                                  @Param("expectedPasswordHash") String expectedPasswordHash,
                                  @Param("newPasswordHash") String newPasswordHash);
}
