# 注释规范

## 类必须有文件级注释

每个 public 类必须有文件级 Javadoc，说明类的职责、作者、关键设计点。

错误示例：
```java
public class UserService {
    // 没有任何注释
}
```

正确示例：
```java
/**
 * 用户服务层，负责用户账号的创建、查询、状态管理。
 *
 * <p>设计要点：
 * <ul>
 *   <li>事务由 @Transactional 声明式管理</li>
 *   <li>密码使用 BCrypt 加密，不可逆</li>
 * </ul>
 */
@Service
public class UserService {
}
```

## 方法必须有 Javadoc

public 方法必须写 Javadoc，包含 @param、@return、@throws 标签。private 方法可只写行内注释。

错误示例：
```java
public User getUser(String id) {
    return userMap.get(id);
}
```

正确示例：
```java
/**
 * 根据 ID 查询用户。
 *
 * @param id 用户 ID，不能为 null
 * @return 用户对象，不存在返回 null
 * @throws IllegalArgumentException 当 id 为 null 时
 */
public User getUser(String id) {
    if (id == null) throw new IllegalArgumentException("id 不能为空");
    return userMap.get(id);
}
```

## 注释要说明"为什么"而不是"做什么"

代码本身已经说明了"做什么"，注释应该解释"为什么"这样做（业务背景、设计决策、避坑提示）。

错误示例：
```java
// 遍历用户列表
for (User user : users) {
    // 调用 sendEmail 方法
    sendEmail(user.getEmail());
}
```

正确示例：
```java
// 这里同步发送而非异步，是因为邮件发送失败需要立即回滚事务
for (User user : users) {
    sendEmail(user.getEmail());
}
```

## 删除注释掉的代码

不要保留注释掉的代码块，版本控制由 Git 负责。注释掉的代码会让维护者困惑：是有意保留？还是临时禁用？

错误示例：
```java
// public void oldMethod() {
//     ...
// }
```

正确做法：直接删除，需要时从 Git 历史恢复。

---

# Stream API

## 不要在 peek 中做副作用

peek 设计用于调试，不应在里面修改状态或产生副作用。应该用 map 或 forEach。

错误示例：
```java
List<User> users = list.stream()
    .peek(u -> u.setName(u.getName().trim()))  // 修改状态，副作用
    .collect(Collectors.toList());
```

正确示例：
```java
List<User> users = list.stream()
    .map(u -> {
        User copy = new User(u);
        copy.setName(u.getName().trim());
        return copy;
    })
    .collect(Collectors.toList());
```

## Collectors.toMap 注意 key 重复

Stream.toMap() 遇到重复 key 会抛 IllegalStateException，必须指定合并函数。

错误示例：
```java
Map<String, User> map = users.stream()
    .collect(Collectors.toMap(User::getName, Function.identity()));
// 同名用户会抛异常
```

正确示例：
```java
Map<String, User> map = users.stream()
    .collect(Collectors.toMap(
        User::getName,
        Function.identity(),
        (a, b) -> a));  // 保留第一个
```

## 避免嵌套 Stream

Stream 嵌套会降低可读性，应该提取为独立方法或用 flatMap。

错误示例：
```java
List<String> result = orders.stream()
    .map(o -> o.getItems().stream()
        .map(Item::getName)
        .collect(Collectors.toList()))
    .collect(Collectors.toList());  // List<List<String>>，还要再 flatMap
```

正确示例：
```java
List<String> result = orders.stream()
    .flatMap(o -> o.getItems().stream())
    .map(Item::getName)
    .collect(Collectors.toList());
```

## 谨慎使用 parallelStream

parallelStream 在小数据量或不纯的函数中可能更慢，甚至导致线程安全问题。

错误示例：
```java
List<Integer> result = new ArrayList<>();
list.parallelStream().forEach(i -> result.add(i * 2));  // ArrayList 非线程安全
```

正确做法：
- 数据量大（>1万）且操作耗时才用 parallelStream
- 确保操作无副作用、无共享可变状态
- 用 collect 而非 forEach 修改外部状态

---

# Optional 规范

## 不要用 isPresent + get

isPresent + get 等价于 null 检查，失去了 Optional 的意义。应该用 ifPresent、map、orElse 等方法。

错误示例：
```java
Optional<User> opt = findUser(id);
if (opt.isPresent()) {
    return opt.get().getName();
}
return "unknown";
```

正确示例：
```java
return findUser(id)
    .map(User::getName)
    .orElse("unknown");
```

## orElse vs orElseGet

orElse 参数是急切求值（无论是否需要都计算），orElseGet 是惰性求值（只有为空才计算）。

错误示例：
```java
// 即使 opt 有值，getDefaultUser() 也会被调用
User u = opt.orElse(getDefaultUser());
```

正确示例：
```java
// 只有 opt 为空才调用 getDefaultUser()
User u = opt.orElseGet(this::getDefaultUser);
```

规则：
- 默认值是常量或已存在对象：用 orElse
- 默认值需要计算（查询 DB、创建对象）：用 orElseGet

## 不要把 Optional 用作字段或参数

Optional 设计用于返回类型，不应作为字段（无法序列化）或方法参数。

错误示例：
```java
public class User {
    private Optional<String> nickname;  // 不应作为字段
}

public void process(Optional<User> user) {  // 不应作为参数
}
```

正确做法：
- 字段直接用 nullable 类型
- 参数用 @Nullable 注解或重载方法

---

# equals 与 hashCode

## 重写 equals 必须重写 hashCode

两个相等的对象必须具有相同的 hashCode，否则在 HashMap/HashSet 中会出问题。

错误示例：
```java
public class User {
    private String id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        return Objects.equals(id, ((User) o).id);
    }
    // 没有重写 hashCode，导致 HashMap 中相同 id 的对象被认为是不同的 key
}
```

正确示例：
```java
public class User {
    private String id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        return Objects.equals(id, ((User) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
```

## equals 对 null 要返回 false

equals 方法对 null 参数应返回 false，不能抛 NPE。

错误示例：
```java
@Override
public boolean equals(Object o) {
    User other = (User) o;  // o 为 null 时 NPE
    return this.id.equals(other.id);
}
```

正确示例：
```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    User other = (User) o;
    return Objects.equals(id, other.id);
}
```

## Lombok @Data 在继承体系下有坑

Lombok @Data 生成的 equals/hashCode 默认不包含父类字段，继承场景下需要手动添加 @EqualsAndHashCode(callSuper = true)。

错误示例：
```java
@Data
public class Admin extends User {  // equals 只比 Admin 自己的字段，漏了 User 的字段
    private String role;
}
```

正确示例：
```java
@Data
@EqualsAndHashCode(callSuper = true)
public class Admin extends User {
    private String role;
}
```

---

# 枚举使用

## 用枚举代替常量

一组相关常量应该用枚举，而非 public static final int，枚举有类型安全、可读性、可扩展性。

错误示例：
```java
public static final int STATUS_PENDING = 1;
public static final int STATUS_RUNNING = 2;
public static final int STATUS_SUCCESS = 3;
public static final int STATUS_FAILED = 4;

// 调用方可以传任意 int，类型不安全
public void setStatus(int status) { }
```

正确示例：
```java
public enum Status {
    PENDING, RUNNING, SUCCESS, FAILED
}

// 类型安全，只能传 Status
public void setStatus(Status status) { }
```

## 枚举实现单例

枚举单例是《Effective Java》推荐的单例实现方式，天然线程安全、防反序列化攻击。

错误示例：
```java
public class Singleton {
    private static Singleton instance;
    private Singleton() {}
    public static Singleton getInstance() {
        if (instance == null) {
            instance = new Singleton();  // 非线程安全
        }
        return instance;
    }
}
```

正确示例：
```java
public enum Singleton {
    INSTANCE;

    public void doSomething() { }
}

// 使用
Singleton.INSTANCE.doSomething();
```

## 枚举的 toString 和 description

枚举的 name() 返回英文常量名，前端展示用应该提供中文 description 或重写 toString。

错误示例：
```java
public enum Severity {
    CRITICAL, MAJOR, MINOR, INFO
}

// 前端直接显示 CRITICAL，不友好
```

正确示例：
```java
public enum Severity {
    CRITICAL("严重"),
    MAJOR("主要"),
    MINOR("次要"),
    INFO("提示");

    private final String description;

    Severity(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
```

---

# 泛型规范

## 通配符：PECS 原则

Producer Extends, Consumer Super：
- 从集合读取数据（生产者）：用 `<? extends T>`
- 向集合写入数据（消费者）：用 `<? super T>`

错误示例：
```java
// 想把 List<Integer> 传给 List<Number> 参数，编译报错
public void process(List<Number> list) { }
process(new ArrayList<Integer>());  // 编译错误
```

正确示例：
```java
// 只读场景：用 extends
public double sum(List<? extends Number> list) {
    double total = 0;
    for (Number n : list) total += n.doubleValue();
    return total;
}
sum(new ArrayList<Integer>());  // OK

// 只写场景：用 super
public void addNumbers(List<? super Integer> list) {
    list.add(1);
    list.add(2);
}
```

## 类型擦除陷阱

泛型在运行时会被擦除，`List<String>` 和 `List<Integer>` 在运行时都是 `List`，不能用 instanceof 区分。

错误示例：
```java
if (list instanceof List<String>) {  // 编译错误
}
```

正确做法：
```java
// 用 Class 对象传递类型信息
public <T> T find(Class<T> clazz, String id) {
    Object obj = map.get(id);
    return clazz.cast(obj);
}
```

## 泛型方法要有意义

不要为了泛型而泛型，如果方法实际上只处理一种类型，就不要用泛型。

错误示例：
```java
// 实际上只返回 String，硬用泛型
public <T> T getValue() {
    return (T) "hello";  // 类型不安全
}
```

正确做法：
```java
public String getValue() {
    return "hello";
}
```
