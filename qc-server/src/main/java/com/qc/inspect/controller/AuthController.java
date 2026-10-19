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
        return R.ok(authService.saveUser(user, req.password()));
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody ResetReq req) {
        authService.resetPassword(id, req.newPassword());
        return R.ok();
    }

    public record LoginReq(String username, String password) {
    }

    public record PasswordReq(String oldPassword, String newPassword) {
    }

    public record UserReq(Long id, String username, String realName, String role,
                          Boolean enabled, String password) {
    }

    public record ResetReq(String newPassword) {
    }
}
