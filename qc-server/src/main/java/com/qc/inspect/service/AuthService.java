package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.auth.JwtUtil;
import com.qc.inspect.auth.LoginUser;
import com.qc.inspect.auth.UserContext;
import com.qc.inspect.common.BizException;
import com.qc.inspect.entity.SysUser;
import com.qc.inspect.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    /** 角色白名单，与 db/V5 的 ck_user_role 约束保持一致 */
    private static final List<String> ROLES = List.of("ADMIN", "INSPECTOR", "REVIEWER");

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw BizException.of("请输入账号和密码");
        }
        SysUser user = findByUsername(username.trim());
        // 账号不存在与密码错误返回同一句，避免被用来枚举账号
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw BizException.of("账号或密码错误");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw BizException.of("账号已停用，请联系管理员");
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRealName(), user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", jwtUtil.issue(loginUser));
        result.put("user", loginUser);
        result.put("expireMinutes", jwtUtil.expireMinutes());
        return result;
    }

    public LoginUser me() {
        LoginUser u = UserContext.current();
        if (u == null) {
            throw BizException.of("未登录");
        }
        return u;
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        LoginUser current = me();
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.of("新密码至少 6 位");
        }
        SysUser user = userMapper.selectById(current.id());
        if (user == null) {
            throw BizException.of("账号不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw BizException.of("原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw BizException.of("新密码不能与原密码相同");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    // ==================== 用户管理（仅 ADMIN） ====================

    public List<SysUser> listUsers() {
        requireAdmin();
        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .orderByAsc(SysUser::getId));
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    @Transactional
    public SysUser saveUser(SysUser req, String rawPassword) {
        requireAdmin();
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw BizException.of("账号不能为空");
        }
        SysUser exist = findByUsername(req.getUsername().trim());
        if (req.getId() == null && exist != null) {
            throw BizException.of("账号已存在：" + req.getUsername());
        }
        if (req.getRole() == null || req.getRole().isBlank()) {
            req.setRole("INSPECTOR");
        }
        if (!ROLES.contains(req.getRole())) {
            throw BizException.of("角色只能是 ADMIN / INSPECTOR / REVIEWER，收到：" + req.getRole());
        }
        req.setUsername(req.getUsername().trim());
        req.setUpdatedAt(LocalDateTime.now());

        if (req.getId() == null) {
            if (rawPassword == null || rawPassword.length() < 6) {
                throw BizException.of("新建账号必须设置至少 6 位密码");
            }
            req.setPassword(passwordEncoder.encode(rawPassword));
            req.setEnabled(req.getEnabled() == null || req.getEnabled());
            userMapper.insert(req);
        } else {
            if (exist != null && !exist.getId().equals(req.getId())) {
                throw BizException.of("账号已被其他用户占用");
            }
            assertNotLastAdmin(req);
            // 未传密码表示不改密码
            req.setPassword(rawPassword == null || rawPassword.isBlank()
                    ? null : passwordEncoder.encode(rawPassword));
            userMapper.updateById(req);
        }
        req.setPassword(null);
        return req;
    }

    /**
     * 不允许把最后一个可用的管理员降级或停用。
     *
     * <p>这不是权限洁癖：一旦系统里没有管理员，标准发布、量具台账、账号维护全部锁死，
     * 只能改数据库才能救回来。宁可在这里拦住。
     */
    private void assertNotLastAdmin(SysUser req) {
        SysUser old = userMapper.selectById(req.getId());
        if (old == null || !"ADMIN".equals(old.getRole())) {
            return;
        }
        boolean stillAdmin = "ADMIN".equals(req.getRole()) && !Boolean.FALSE.equals(req.getEnabled());
        if (stillAdmin) {
            return;
        }
        long activeAdmins = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getRole, "ADMIN")
                .eq(SysUser::getEnabled, true));
        if (activeAdmins <= 1) {
            throw BizException.of("这是最后一个可用管理员，不能降级或停用。请先新建另一个管理员账号");
        }
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        requireAdmin();
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.of("密码至少 6 位");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw BizException.of("仅管理员可执行该操作");
        }
    }

    private SysUser findByUsername(String username) {
        LambdaQueryWrapper<SysUser> q = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .last("limit 1");
        return userMapper.selectOne(q);
    }
}
