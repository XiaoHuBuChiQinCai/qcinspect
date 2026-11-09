package com.qc.inspect.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qc.inspect.auth.JwtUtil;
import com.qc.inspect.auth.LoginLock;
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

    /**
     * 账号不存在时用来「空转」一次 BCrypt 的占位哈希（"password" 的 BCrypt）。
     *
     * <p>不做这一步，登录接口就会用响应时间回答「这个账号存不存在」：
     * 账号不存在时直接 return，账号存在时要跑一遍 BCrypt（几十毫秒），
     * 差一个数量级，拿脚都能测出来。所以查不到人也要照样算一次，把两条路的时间拉平。
     *
     * <p>用真实的 BCrypt 串而不是随便一段字符：编码器认不出格式时会直接返回 false，
     * 一次哈希都不算，那就白做了。
     */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public Map<String, Object> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw BizException.of("请输入账号和密码");
        }
        SysUser user = findByUsername(username.trim());
        LocalDateTime now = LocalDateTime.now();

        // 先判锁，再看密码。顺序不能反：先验密码再判锁，等于锁了个寂寞——
        // 密码一旦被猜对，这里就是它出场的唯一时机。
        long minutesLeft = user == null ? 0 : LoginLock.minutesLeft(user.getLockedUntil(), now);
        if (minutesLeft > 0) {
            throw BizException.of(LoginLock.lockedMessage(minutesLeft));
        }

        // 账号不存在与密码错误返回同一句，避免被用来枚举账号
        boolean matched = user != null
                ? passwordEncoder.matches(password, user.getPassword())
                : burned(password);
        if (!matched) {
            if (user == null) {
                throw BizException.of("账号或密码错误");
            }
            int before = user.getFailedAttempts() == null ? 0 : user.getFailedAttempts();
            userMapper.countFailure(user.getId(), now,
                    now.plusMinutes(LoginLock.LOCK_MINUTES), LoginLock.MAX_FAILURES);
            // 正好这一次锁上的话必须说清楚，否则人会以为「密码还是错」，接着再试再撞墙
            throw BizException.of(LoginLock.willLock(before)
                    ? LoginLock.justLockedMessage() : "账号或密码错误");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw BizException.of("账号已停用，请联系管理员");
        }
        // 成功即清零：连续错误是「连续」的事，中间成功过一次就重新计数
        if (user.getFailedAttempts() != null && user.getFailedAttempts() > 0) {
            userMapper.clearFailure(user.getId());
        }

        LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRealName(), user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", jwtUtil.issue(loginUser));
        result.put("user", loginUser);
        result.put("expireMinutes", jwtUtil.expireMinutes());
        return result;
    }

    /** 查不到人时也照样跑一次哈希，只为把两条分支的耗时拉平 */
    private boolean burned(String password) {
        passwordEncoder.matches(password, DUMMY_HASH);
        return false;
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
        LocalDateTime now = LocalDateTime.now();
        users.forEach(u -> {
            u.setPassword(null);
            // 锁没锁、还剩几分钟，一律由服务端算好再下发。
            // 让浏览器自己拿 lockedUntil 和本地时间比，时钟差几分钟就会出现
            // 「页面显示已锁定、人其实能登」这种谁也说不清的状态
            long left = LoginLock.minutesLeft(u.getLockedUntil(), now);
            u.setLocked(left > 0);
            u.setLockMinutes(left > 0 ? left : null);
        });
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
        normalizeWecom(req);

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
            boolean newPassword = rawPassword != null && !rawPassword.isBlank();
            req.setPassword(newPassword ? passwordEncoder.encode(rawPassword) : null);
            userMapper.updateById(req);
            // 换了密码就顺带解锁：否则出现「密码已重置但人还是登不进去」
            if (newPassword) {
                clearFailureQuietly(req.getId());
            }
            if (req.getWecomUserid() == null) {
                // updateById 默认忽略 null，解绑时不清一次就永远清不掉
                userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, req.getId())
                        .set(SysUser::getWecomUserid, null));
            }
        }
        req.setPassword(null);
        return req;
    }

    /**
     * 整理并校验企业微信账号。
     *
     * <p>三条都是「在界面上填错、在运行时才炸」的典型：
     * <ul>
     *   <li>前后带空格 —— 企业微信不认，报「账号不存在」，而人眼看不出区别</li>
     *   <li>填成手机号或姓名 —— 这里拦一句，比等到推送失败再查便宜得多</li>
     *   <li>一个账号绑两个人 —— 同一条退回会发两遍，且两个人都会以为自己该处理</li>
     * </ul>
     */
    private void normalizeWecom(SysUser req) {
        String wx = req.getWecomUserid();
        if (wx != null) {
            wx = wx.trim();
            if (wx.isEmpty()) {
                wx = null;
            }
        }
        if (wx != null) {
            if (wx.length() > 64) {
                throw BizException.of("企业微信账号过长（最多 64 个字符）");
            }
            if (wx.chars().anyMatch(Character::isWhitespace)) {
                throw BizException.of("企业微信账号里不能有空格");
            }
            long busy = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getWecomUserid, wx)
                    .ne(req.getId() != null, SysUser::getId, req.getId()));
            if (busy > 0) {
                throw BizException.of("该企业微信账号已绑定其他账号：" + wx);
            }
        }
        req.setWecomUserid(wx);
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
        // 管理员刚把密码换成新的，之前那几次错误就不该再压着这个人：
        // 不清的话，人拿到新密码却仍然登不进去（提示「账号已锁定」），
        // 只会再打一次电话问「你不是说重置好了吗」
        clearFailureQuietly(userId);
    }

    /**
     * 管理员手工解锁。
     *
     * <p>存在的理由是「十五分钟也很长」：产线停在那里等人上班，而重置密码本来就要管理员动手，
     * 顺手能解就没人会去改数据库。
     */
    @Transactional
    public void unlock(Long userId) {
        requireAdmin();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of("账号不存在");
        }
        clearFailureQuietly(userId);
    }

    private void clearFailureQuietly(Long userId) {
        if (userId != null) {
            userMapper.clearFailure(userId);
        }
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
