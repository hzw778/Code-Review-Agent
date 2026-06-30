# 命名规范

## 类名使用 UpperCamelCase

类名必须使用大驼峰命名法（UpperCamelCase），首字母大写，每个单词首字母大写。

正确示例：
- UserService
- OrderController
- UserController

错误示例：
- userService（首字母小写）
- user_service（下划线分隔）
- USER_SERVICE（全大写）

## 方法名使用 lowerCamelCase

方法名必须使用小驼峰命名法（lowerCamelCase），首字母小写，后续单词首字母大写。

正确示例：
- getUserById()
- calculateTotal()
- isUserActive()

错误示例：
- GetUserById()（首字母大写）
- get_user_by_id()（下划线分隔）

## 变量名使用 lowerCamelCase

变量名必须使用小驼峰命名法（lowerCamelCase），首字母小写，后续单词首字母大写。

正确示例：
- userName
- orderList
- itemCount

错误示例：
- UserName（首字母大写）
- user_name（下划线分隔）
- URL（全大写，除非是常量）

## 常量使用 UPPER_SNAKE_CASE

常量（static final 字段）必须使用全大写加下划线分隔命名法（UPPER_SNAKE_CASE）。

正确示例：
- MAX_RETRY_COUNT
- DEFAULT_TIMEOUT
- PI

错误示例：
- maxRetryCount（驼峰）
- maxretrycount（全小写）

---

# 异常处理

## 禁止空的 catch 块

空的 catch 块会吞掉异常，导致问题难以排查，是严重的代码质量问题。

错误示例：
```java
try {
    riskyOperation();
} catch (Exception e) {
    // 空的 catch 块，问题被吞掉
}
```

正确做法：
- 至少记录日志：log.error("操作失败", e);
- 或重抛异常：throw new RuntimeException(e);
- 或包装成业务异常：throw new BusinessException(ErrorCode.OPERATION_FAILED, e);

## 不要 catch Exception，要 catch 具体异常

捕获 Exception 会捕获所有异常（包括 RuntimeException 和 Error），可能掩盖编程错误。

错误示例：
```java
try {
    // ...
} catch (Exception e) {
    // 捕获范围太广
}
```

正确做法：
- catch 具体的异常类型：catch (IOException e) 或 catch (SQLException e)
- 分别处理不同异常：多个 catch 块，每个处理一类异常

## finally 块禁止 return

finally 块中的 return 会覆盖 try 块中的 return，导致逻辑错误且难以排查。

错误示例：
```java
public int getValue() {
    try {
        return 1;
    } finally {
        return 2;  // 会覆盖 try 的 return 1，实际返回 2
    }
}
```

正确做法：finally 块只做资源清理，不写 return 语句。

## 异常信息要有上下文

抛异常时必须包含足够的上下文信息（参数值、业务场景），方便排查问题。

错误示例：
```java
throw new IllegalArgumentException("参数错误");
```

正确示例：
```java
throw new IllegalArgumentException("用户ID不能为空, 当前值: " + userId);
```

---

# 资源管理

## 使用 try-with-resources 自动关闭资源

所有实现 AutoCloseable 接口的资源（InputStream、Connection、Statement 等）必须使用 try-with-resources 自动关闭，避免资源泄漏。

错误示例：
```java
FileInputStream fis = new FileInputStream("file.txt");
// 手动 close 容易忘记，异常时也会泄漏
fis.close();
```

正确示例：
```java
try (FileInputStream fis = new FileInputStream("file.txt")) {
    // 使用 fis
}  // 自动 close，即使异常也会关闭
```

## 不要在循环里创建连接

在循环中创建数据库连接、网络连接会消耗大量资源，导致连接池耗尽。

错误示例：
```java
for (User user : users) {
    Connection conn = dataSource.getConnection();  // 每次循环都创建连接
    // ...
    conn.close();
}
```

正确做法：在循环外创建连接，循环内复用。

## 资源关闭顺序

多个资源关闭时，应该按"后开先关"的顺序关闭（与创建顺序相反）。

正确示例：
```java
try (Connection conn = dataSource.getConnection();
     PreparedStatement ps = conn.prepareStatement(sql);
     ResultSet rs = ps.executeQuery()) {
    // 顺序：conn → ps → rs
    // 自动关闭顺序：rs → ps → conn（反向）
}
```

---

# 方法设计

## 方法不要过长

单个方法的语句数不应超过 30 条，方法过长通常意味着职责不单一，难以维护和测试。

过长方法的问题：
- 职责不单一，难以复用
- 难以测试，单元测试覆盖困难
- 阅读困难，可维护性差

正确做法：
- 拆分为多个职责单一的小方法
- 每个方法只做一件事
- 方法名清晰表达意图，注释只是补充

## 方法参数不要超过 5 个

方法参数过多通常意味着方法职责过重，或应该封装成对象。

错误示例：
```java
public void createUser(String name, int age, String email, String phone, String address, String idCard) {
    // 6 个参数
}
```

正确做法：封装成 UserDTO 对象传入。

## 避免魔法数字

代码中直接使用数字常量会降低可读性，应该提取为有意义的常量。

错误示例：
```java
if (retryCount > 3) {  // 3 是什么含义？
    return;
}
Thread.sleep(5000);  // 5000 是什么单位？
```

正确示例：
```java
private static final int MAX_RETRY_COUNT = 3;
private static final long SLEEP_DURATION_MS = 5000L;

if (retryCount > MAX_RETRY_COUNT) {
    return;
}
Thread.sleep(SLEEP_DURATION_MS);
```

例外：0、1、2 这些常用值可以不提取为常量。

---

# 控制语句

## if 语句必须加大括号

if 语句必须使用大括号，即使只有一行代码，避免后续维护时出错。

错误示例：
```java
if (isValid)
    doSomething();  // 没有大括号
```

正确示例：
```java
if (isValid) {
    doSomething();
}
```

## switch 必须有 default

switch 语句必须有 default 分支，处理未覆盖的情况，避免逻辑遗漏。

错误示例：
```java
switch (status) {
    case 1: doA(); break;
    case 2: doB(); break;
    // 没有 default
}
```

正确示例：
```java
switch (status) {
    case 1: doA(); break;
    case 2: doB(); break;
    default: throw new IllegalArgumentException("未知状态: " + status);
}
```

## 循环中不要做数据库查询

在循环中执行数据库查询会导致 N+1 问题，严重影响性能。

错误示例：
```java
for (Order order : orders) {
    User user = userRepository.findById(order.getUserId());  // N+1 查询
}
```

正确做法：批量查询，一次取出所有需要的数据。

---

# 集合处理

## 空集合返回而不是 null

方法返回集合时，应该返回空集合而不是 null，避免调用方需要做 null 检查。

错误示例：
```java
public List<User> findUsers() {
    if (noUsers) {
        return null;  // 调用方要判 null
    }
    return users;
}
```

正确示例：
```java
public List<User> findUsers() {
    if (noUsers) {
        return Collections.emptyList();  // 返回空集合
    }
    return users;
}
```

## 使用 Iterator 遍历时不要修改集合

使用 Iterator 遍历集合时，不要直接修改集合（add/remove），会抛出 ConcurrentModificationException。

错误示例：
```java
for (User user : users) {
    if (user.isDeleted()) {
        users.remove(user);  // 抛 ConcurrentModificationException
    }
}
```

正确做法：使用 Iterator.remove() 或 Java 8 的 removeIf。

## 集合转 Map 时注意 key 冲突

使用 Stream.toMap() 时，如果 key 重复会抛 IllegalStateException，需要指定合并函数。

错误示例：
```java
Map<String, User> map = users.stream()
    .collect(Collectors.toMap(User::getName, Function.identity()));  // 同名会冲突
```

正确示例：
```java
Map<String, User> map = users.stream()
    .collect(Collectors.toMap(User::getName, Function.identity(), (a, b) -> a));  // 保留第一个
```
