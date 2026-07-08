package sample;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional 与空指针防御最佳实践示例
 *
 * <p>演示 Optional 的正确用法，避免 isPresent+get 反模式，
 * 正确使用 orElseGet、map 链式调用。
 */
public class OptionalBestPracticeSample {

    /**
     * 正确：方法返回 Optional
     *
     * <p>显式表示可能为空，调用方必须处理。
     */
    public Optional<User> findUser(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        User user = userMap.get(id);
        return Optional.ofNullable(user);
    }

    /**
     * 正确：用 map 链式调用避免 NPE
     *
     * <p>等价于多次 null 检查，但更简洁。
     */
    public String getUserCity(String id) {
        return findUser(id)
                .map(User::getAddress)
                .map(Address::getCity)
                .orElse("未知");
    }

    /**
     * 正确：用 ifPresent 消费结果
     *
     * <p>避免 isPresent + get 反模式。
     */
    public void printUser(String id) {
        findUser(id).ifPresent(u ->
                System.out.println("用户: " + u.getName()));
    }

    /**
     * 正确：orElseGet 惰性求值
     *
     * <p>默认值需要计算时用 orElseGet，避免不必要的开销。
     */
    public User getOrCreate(String id) {
        return findUser(id).orElseGet(() -> createDefaultUser(id));
    }

    /**
     * 正确：orElse 用于常量默认值
     *
     * <p>默认值是常量时用 orElse，性能无差别。
     */
    public String getDisplayName(String id) {
        return findUser(id)
                .map(User::getName)
                .orElse("匿名用户");
    }

    /**
     * 正确：用 orElseThrow 抛业务异常
     *
     * <p>期望值一定存在，不存在则是异常情况。
     */
    public User requireUser(String id) {
        return findUser(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    /**
     * 正确：filter 过滤
     */
    public Optional<User> findActiveUser(String id) {
        return findUser(id).filter(User::isActive);
    }

    /**
     * 正确：参数校验用 Objects.requireNonNull
     *
     * <p>在方法入口就抛 NPE，定位准确。
     */
    public void process(User user) {
        Objects.requireNonNull(user, "user 不能为 null");
        Objects.requireNonNull(user.getName(), "user.name 不能为 null");
        System.out.println("处理用户: " + user.getName());
    }

    // ===== 错误反例（仅注释，不可调用） =====
    // 错误：isPresent + get，等价于 null 检查，失去 Optional 意义
    // if (opt.isPresent()) { return opt.get().getName(); }
    // 正确：opt.map(User::getName).orElse("unknown");

    // ===== 私有辅助方法 =====
    private User createDefaultUser(String id) {
        User u = new User();
        u.setName("默认用户");
        return u;
    }

    // ===== 假数据源 =====
    private final java.util.Map<String, User> userMap = new java.util.HashMap<>();

    // ===== 内部类 =====
    static class User {
        private String name;
        private Address address;
        private boolean active;

        public String getName() { return name; }
        public Address getAddress() { return address; }
        public boolean isActive() { return active; }
        public void setName(String name) { this.name = name; }
    }

    static class Address {
        private String city;
        public String getCity() { return city; }
    }
}
