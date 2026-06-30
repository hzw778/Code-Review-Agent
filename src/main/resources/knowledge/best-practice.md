# 空指针防御

## 使用 Optional 代替 null

Java 8 引入的 Optional 可以显式表示"可能为空"的值，比直接返回 null 更安全。

错误示例：
```java
public User findUser(String id) {
    return userMap.get(id);  // 可能返回 null
}

User user = findUser("123");
user.getName();  // 可能 NPE
```

正确示例：
```java
public Optional<User> findUser(String id) {
    return Optional.ofNullable(userMap.get(id));
}

findUser("123").ifPresent(user -> user.getName());
```

## 使用 Objects.requireNonNull 做参数校验

方法参数如果是 null，应该在方法入口就抛出 NullPointerException，而不是在后续使用时才报错。

错误示例：
```java
public void process(User user) {
    // 不校验，后面可能 NPE，且报错位置不准确
    String name = user.getName();
}
```

正确示例：
```java
public void process(User user) {
    Objects.requireNonNull(user, "user 不能为 null");
    String name = user.getName();
}
```

## 链式调用注意中间环节

链式调用时，如果中间环节为 null，会抛 NPE，应该用 Optional 链式调用或安全调用。

错误示例：
```java
String city = user.getAddress().getCity();  // user 或 address 可能为 null
```

正确示例：
```java
String city = Optional.ofNullable(user)
    .map(User::getAddress)
    .map(Address::getCity)
    .orElse("未知");
```

---

# 集合使用

## 指定集合初始容量

创建 ArrayList、HashMap 时指定初始容量，避免频繁扩容影响性能。

错误示例：
```java
List<String> list = new ArrayList<>();  // 默认容量 10，超出后扩容
Map<String, String> map = new HashMap<>();  // 默认容量 16
```

正确示例：
```java
List<String> list = new ArrayList<>(100);  // 已知要存 100 个
Map<String, String> map = new HashMap<>(256);  // 已知要存 200 个
```

## 使用不可变集合

如果集合不需要修改，使用 Collections.unmodifiableXxx 或 List.of() 创建不可变集合，防止意外修改。

错误示例：
```java
public List<String> getKeywords() {
    return keywords;  // 调用方可以修改内部状态
}
```

正确示例：
```java
public List<String> getKeywords() {
    return Collections.unmodifiableList(keywords);
    // 或 Java 9+: return List.copyOf(keywords);
}
```

## 使用合适的集合类型

根据场景选择合适的集合类型，避免性能问题。

错误示例：
```java
List<String> list = new ArrayList<>();
if (list.contains("a")) {  // ArrayList 的 contains 是 O(n)
}
```

正确示例：
```java
Set<String> set = new HashSet<>();
if (set.contains("a")) {  // HashSet 的 contains 是 O(1)
}
```

---

# 字符串处理

## 使用 StringBuilder 拼接字符串

循环中拼接字符串应该使用 StringBuilder，避免创建大量 String 对象。

错误示例：
```java
String result = "";
for (String item : items) {
    result += item;  // 每次创建新 String 对象
}
```

正确示例：
```java
StringBuilder sb = new StringBuilder();
for (String item : items) {
    sb.append(item);
}
String result = sb.toString();
```

## 使用 String.format 或 MessageFormat 替代字符串拼接

复杂的字符串拼接应该使用 String.format，提高可读性。

错误示例：
```java
String msg = "用户 " + name + " 的年龄是 " + age + " 岁";
```

正确示例：
```java
String msg = String.format("用户 %s 的年龄是 %d 岁", name, age);
```

## 注意字符串编码

处理中文、特殊字符时要注意编码问题，统一使用 UTF-8。

错误示例：
```java
byte[] bytes = str.getBytes();  // 使用系统默认编码，不同环境结果不同
```

正确示例：
```java
byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
String str = new String(bytes, StandardCharsets.UTF_8);
```

---

# 日志规范

## 使用占位符而不是字符串拼接

日志使用 {} 占位符，避免不必要的字符串拼接开销。

错误示例：
```java
log.debug("用户 " + userId + " 登录成功");
```

正确示例：
```java
log.debug("用户 {} 登录成功", userId);
```

## 异常日志要打印完整堆栈

记录异常日志时，要把异常对象作为最后一个参数，让日志框架打印完整堆栈。

错误示例：
```java
log.error("操作失败: " + e.getMessage());  // 丢失堆栈信息
```

正确示例：
```java
log.error("操作失败", e);  // e 作为最后一个参数，打印完整堆栈
```

## 不同日志级别的使用场景

| 级别 | 使用场景 |
|------|---------|
| ERROR | 系统错误、不可恢复的故障、需要立即处理 |
| WARN | 可恢复的异常、降级处理、业务警告 |
| INFO | 关键业务节点、数据流转、接口调用 |
| DEBUG | 调试信息、中间结果、详细参数 |
| TRACE | 极细粒度的执行追踪 |

不要用 ERROR 记录业务正常流程，不要用 INFO 记录详细调试信息。

## 敏感信息脱敏

日志中禁止打印密码、token、身份证号等敏感信息，必要时要做脱敏处理。

错误示例：
```java
log.info("用户登录, 密码: " + password);
```

正确示例：
```java
log.info("用户登录, 密码: ******");
// 或使用脱敏工具
log.info("用户登录, 密码: {}", DesensitizeUtil.mask(password));
```

---

# 并发编程

## 使用线程池而不是 new Thread

创建线程应该使用线程池（ExecutorService），避免频繁创建销毁线程的开销。

错误示例：
```java
new Thread(() -> doSomething()).start();  // 频繁创建销毁
```

正确示例：
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
executor.submit(() -> doSomething());
```

## 使用线程安全的集合

多线程环境下，使用 ConcurrentHashMap、CopyOnWriteArrayList 等线程安全集合。

错误示例：
```java
Map<String, String> map = new HashMap<>();  // 多线程下不安全
```

正确示例：
```java
Map<String, String> map = new ConcurrentHashMap<>();
```

## 加锁范围要小

synchronized 块的范围应该尽量小，只保护必要的代码，避免影响性能。

错误示例：
```java
public synchronized void process() {
    // 整个方法都加锁，包括不需要同步的代码
    log.info("开始处理");
    doBusinessLogic();
    log.info("处理完成");
}
```

正确示例：
```java
public void process() {
    log.info("开始处理");
    synchronized(this) {
        doBusinessLogic();  // 只锁需要同步的部分
    }
    log.info("处理完成");
}
```

## 避免死锁

多把锁时，要保证加锁顺序一致，避免死锁。

错误示例：
```java
// 线程 A
synchronized(lockA) {
    synchronized(lockB) { ... }
}

// 线程 B
synchronized(lockB) {
    synchronized(lockA) { ... }  // 加锁顺序不同，可能死锁
}
```

正确示例：所有线程按相同顺序加锁（如先 A 后 B）。

---

# IO 操作

## 使用缓冲流提升性能

读写文件应该使用缓冲流（BufferedReader、BufferedWriter），减少磁盘 IO 次数。

错误示例：
```java
FileInputStream fis = new FileInputStream("file.txt");
int data = fis.read();  // 每次读一个字节
```

正确示例：
```java
try (BufferedReader reader = new BufferedReader(new FileReader("file.txt"))) {
    String line = reader.readLine();  // 一次读一行，有缓冲
}
```

## 使用 NIO 处理大文件

处理大文件（GB 级别）应该使用 NIO 的 FileChannel 和 ByteBuffer，避免 OOM。

错误示例：
```java
byte[] data = Files.readAllBytes(Paths.get("bigfile.dat"));  // 大文件会 OOM
```

正确示例：
```java
try (FileChannel channel = FileChannel.open(Paths.get("bigfile.dat"))) {
    ByteBuffer buffer = ByteBuffer.allocate(8192);
    while (channel.read(buffer) > 0) {
        buffer.flip();
        // 处理 buffer
        buffer.clear();
    }
}
```

## 使用 try-with-resources 确保资源关闭

所有 IO 资源必须使用 try-with-resources，确保即使异常也能关闭。

错误示例：
```java
FileInputStream fis = new FileInputStream("file.txt");
// 如果这里抛异常，fis 不会被关闭
fis.close();
```

正确示例：
```java
try (FileInputStream fis = new FileInputStream("file.txt")) {
    // 使用 fis
}  // 自动关闭
```

---

# 数据库操作

## 使用 PreparedStatement 防止 SQL 注入

拼接 SQL 字符串会导致 SQL 注入漏洞，必须使用 PreparedStatement。

错误示例：
```java
String sql = "SELECT * FROM user WHERE name = '" + name + "'";
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery(sql);  // SQL 注入风险
```

正确示例：
```java
String sql = "SELECT * FROM user WHERE name = ?";
PreparedStatement ps = conn.prepareStatement(sql);
ps.setString(1, name);
ResultSet rs = ps.executeQuery();
```

## 使用连接池管理数据库连接

不要每次操作都创建新连接，应该使用连接池（HikariCP、Druid）。

错误示例：
```java
Connection conn = DriverManager.getConnection(url, user, password);  // 每次创建新连接
```

正确示例：
```java
DataSource dataSource = new HikariDataSource(config);
Connection conn = dataSource.getConnection();  // 从连接池获取
```

## 事务范围要小

事务应该只包含必要的数据库操作，避免在事务中执行耗时操作（如远程调用）。

错误示例：
```java
@Transactional
public void process() {
    // 调用远程接口（耗时）
    remoteService.call();
    // 数据库操作
    userRepository.save(user);
}
```

正确示例：将远程调用移出事务，或拆分成多个方法。
