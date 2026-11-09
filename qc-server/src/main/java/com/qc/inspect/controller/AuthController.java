package com.qc.inspect.controller;

import com.qc.inspect.auth.LoginUser;
import com.qc.inspect.common.R;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req) {
        return R.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public R<LoginUser> me() {
        return R.ok(authService.me());
    }

    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody PasswordReq req) {
        authService.changePassword(req.oldPassword(), req.newPassword());
        return R.ok();
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<SysUser>> users() {
        return R.ok(authService.listUsers());
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public R<SysUser> saveUser(@RequestBody UserReq req) {
        SysUser user = new SysUser();
        user.setId(req.id());
        user.setUsername(req.username());
        user.setRealName(req.realName());
        user.setRole(req.role());
        user.setEnabled(req.enabled());
        user.setWecomUserid(req.wecomUserid());
        return R.ok(authService.saveUser(user, req.password()));
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody ResetReq req) {
        authService.resetPassword(id, req.newPassword());
        return R.ok();
    }

    /**
     * 解锁一个被「连续密码错误」锁住的账号。
     *
     * <p>锁本来就会在 15 分钟后自己解开，这个接口是给「等不了」的场景：
     * 人站在产线旁打电话说登不上，管理员顺手点一下，比让他盯着表等十五分钟合理。
     */
    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> unlock(@PathVariable Long id) {
        authService.unlock(id);
        return R.ok();
    }

    public record LoginReq(String username, String password) {
    }

    public record PasswordReq(String oldPassword, String newPassword) {
    }

    public record UserReq(Long id, String username, String realName, String role,
                          Boolean enabled, String password, String wecomUserid) {
    }

    public record ResetReq(String newPassword) {
    }
}
