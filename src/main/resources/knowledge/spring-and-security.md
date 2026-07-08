# Spring 框架规范

## @Transactional 不要加在 private 方法上

Spring AOP 通过代理调用方法，private 方法不会被代理，@Transactional 不生效。

错误示例：
```java
@Service
public class UserService {
    @Transactional  // 不生效，private 方法无法被代理
    private void createUser(User user) {
        userRepository.save(user);
    }
}
```

正确示例：
```java
@Service
public class UserService {
    @Transactional
    public void createUser(User user) {
        userRepository.save(user);
    }
}
```

## @Transactional 自调用失效

同一个类内方法 A 调用方法 B，B 上的 @Transactional 不生效（this 调用不经过代理）。

错误示例：
```java
@Service
public class UserService {
    public void batchCreate(List<User> users) {
        for (User u : users) {
            this.createUser(u);  // this 调用，@Transactional 失效
        }
    }

    @Transactional
    public void createUser(User user) {
        userRepository.save(user);
    }
}
```

正确做法：
- 方案 1：把 @Transactional 加到 batchCreate 上
- 方案 2：通过 AopContext.currentProxy() 调用
- 方案 3：拆分到两个类

## @Async 自调用同样失效

与 @Transactional 同理，@Async 方法被同类内部调用不会走代理，仍是同步执行。

错误示例：
```java
@Service
public class ReviewService {
    public void submitReview() {
        this.runReviewAsync();  // 同步执行，没走代理
    }

    @Async
    public void runReviewAsync() { }
}
```

正确做法：拆分到独立 Service 类，或注入自身代理。

## 依赖注入用构造器而非 @Autowired 字段

构造器注入是 Spring 官方推荐方式，字段不可变、便于测试、避免循环依赖。

错误示例：
```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;  // 字段注入，不可变、难测试
}
```

正确示例：
```java
@Service
@RequiredArgsConstructor  // Lombok 自动生成构造器
public class UserService {
    private final UserRepository userRepository;  // final 保证不可变
}
```

## 多 ChatClient 时用 @Qualifier

Spring 容器有多个同类型 Bean 时，必须用 @Qualifier 指定注入哪一个，否则启动报错。

错误示例：
```java
@Service
public class ChatService {
    @Autowired
    private ChatClient chatClient;  // 有多个 ChatClient 时报错
}
```

正确示例：
```java
@Service
public class ChatService {
    private final ChatClient chatClient;

    public ChatService(@Qualifier("chatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }
}
```

注意：Lombok @RequiredArgsConstructor 不会保留参数上的 @Qualifier，多 Bean 场景必须手写构造器。

## 不要在 Controller 层写业务逻辑

Controller 只做参数校验和调用 Service，业务逻辑应在 Service 层。

错误示例：
```java
@RestController
public class UserController {
    @PostMapping
    public User create(@RequestBody UserDTO dto) {
        User user = new User();
        user.setName(dto.getName().trim());
        user.setCreatedAt(LocalDateTime.now());
        // 在 Controller 里做密码加密、默认值设置等业务逻辑
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        return userRepository.save(user);
    }
}
```

正确示例：
```java
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    public User create(@RequestBody @Valid UserDTO dto) {
        return userService.create(dto);
    }
}
```

## Repository 方法命名规范

Spring Data JPA 的方法名查询要见名知意，复杂查询用 @Query。

错误示例：
```java
// 方法名太长，难读
User findByEmailAndNameAndAge(String email, String name, int age);
```

正确示例：
```java
@Query("SELECT u FROM User u WHERE u.email = :email AND u.name = :name AND u.age = :age")
User findByCondition(@Param("email") String email,
                     @Param("name") String name,
                     @Param("age") int age);
```

## JPA 实体不要用 @Data

Lombok @Data 生成的 toString 在双向关联实体上会递归调用，导致 StackOverflow。

错误示例：
```java
@Data  // 生成 toString 遍历所有字段，包括关联实体
@Entity
public class ReviewRecord {
    @OneToMany
    private List<ReviewIssue> issues;
}

@Data
@Entity
public class ReviewIssue {
    @ManyToOne
    private ReviewRecord reviewRecord;  // 循环引用，toString 栈溢出
}
```

正确做法：
- 用 @Getter @Setter 代替 @Data
- 或在关联字段加 @ToString.Exclude

## @Async 线程池要自定义

默认 @Async 用 SimpleAsyncTaskExecutor，每次创建新线程，不复用。生产环境必须自定义线程池。

错误示例：
```java
@Async  // 用默认 SimpleAsyncTaskExecutor，线程不复用
public void sendEmail() { }
```

正确示例：
```java
@Configuration
public class AsyncConfig {
    @Bean("reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("review-");
        executor.initialize();
        return executor;
    }
}

@Async("reviewExecutor")  // 指定线程池
public void sendEmail() { }
```

---

# 性能与内存

## 大集合分页处理

处理大集合时应该分页或分批，避免一次性加载到内存导致 OOM。

错误示例：
```java
List<Order> all = orderRepository.findAll();  // 百万级数据直接 OOM
for (Order o : all) {
    process(o);
}
```

正确示例：
```java
int page = 0;
int size = 1000;
Page<Order> p;
do {
    p = orderRepository.findAll(PageRequest.of(page, size));
    for (Order o : p.getContent()) {
        process(o);
    }
    page++;
} while (!p.isLast());
```

## 字符串拼接在循环中用 StringBuilder

循环内字符串拼接会创建大量临时对象，应该用 StringBuilder。

错误示例：
```java
String result = "";
for (String item : items) {
    result += item;  // 每次创建新 String，O(n²)
}
```

正确示例：
```java
StringBuilder sb = new StringBuilder(items.size() * 10);
for (String item : items) {
    sb.append(item);
}
String result = sb.toString();
```

## 使用基本类型而非包装类型

能用 int 的地方不要用 Integer，包装类型有装箱拆箱开销和 NPE 风险。

错误示例：
```java
Integer count = 0;  // 装箱
for (int i = 0; i < 100; i++) {
    count++;  // 每次都拆箱+装箱
}
```

正确示例：
```java
int count = 0;
for (int i = 0; i < 100; i++) {
    count++;
}
```

## 集合指定初始容量

ArrayList、HashMap 创建时指定初始容量，避免频繁扩容。

错误示例：
```java
List<String> list = new ArrayList<>();  // 默认容量 10
for (int i = 0; i < 1000; i++) {
    list.add("item" + i);  // 扩容多次，每次复制数组
}
```

正确示例：
```java
List<String> list = new ArrayList<>(1000);  // 预知大小，一次性分配
for (int i = 0; i < 1000; i++) {
    list.add("item" + i);
}
```

## 谨慎使用 @OneToMany Fetch=EAGER

默认 @OneToMany 是 LAZY，改成 EAGER 会导致加载一个实体就查出所有关联数据，N+1 问题严重。

错误示例：
```java
@OneToMany(fetch = FetchType.EAGER)  // 每次加载 User 都查出所有 Order
private List<Order> orders;
```

正确做法：
- 保持默认 LAZY
- 需要时用 JOIN FETCH 一次性加载
- 或用 @EntityGraph

---

# 安全规范

## SQL 注入防护

拼接 SQL 字符串会导致注入漏洞，必须用 PreparedStatement 或 JPA 参数化查询。

错误示例：
```java
String sql = "SELECT * FROM user WHERE name = '" + name + "'";
Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery(sql);  // 输入 ' OR '1'='1 可绕过
```

正确示例：
```java
// JPA 方式
@Query("SELECT u FROM User u WHERE u.name = :name")
User findByName(@Param("name") String name);

// 原生方式
String sql = "SELECT * FROM user WHERE name = ?";
PreparedStatement ps = conn.prepareStatement(sql);
ps.setString(1, name);
```

## XSS 防护

输出到 HTML 的内容必须转义，防止 XSS 攻击。

错误示例：
```java
@GetMapping("/search")
public String search(@RequestParam String q) {
    return "<div>" + q + "</div>";  // 输入 <script> 会执行
}
```

正确做法：
- 用 Thymeleaf 默认转义
- 或用 OWASP Java HTML Sanitizer

## 敏感信息不要打印日志

密码、token、身份证号、银行卡号等敏感信息禁止打印到日志。

错误示例：
```java
log.info("用户登录, 账号={}, 密码={}", username, password);
log.info("支付完成, 卡号={}", cardNumber);
```

正确示例：
```java
log.info("用户登录, 账号={}", username);
log.info("支付完成, 卡号尾号={}", cardNumber.substring(cardNumber.length() - 4));
```

## 文件上传校验

上传文件必须校验类型、大小、内容，防止上传恶意文件。

错误示例：
```java
@PostMapping("/upload")
public String upload(@RequestParam MultipartFile file) throws IOException {
    File dest = new File("/uploads/" + file.getOriginalFilename());
    file.transferTo(dest);  // 未校验，可能上传 .jsp/.sh
    return "上传成功";
}
```

正确做法：
```java
@PostMapping("/upload")
public String upload(@RequestParam MultipartFile file) throws IOException {
    // 1. 校验大小
    if (file.getSize() > 10 * 1024 * 1024) {
        throw new BusinessException("文件不能超过10MB");
    }
    // 2. 校验扩展名
    String name = file.getOriginalFilename();
    if (!name.endsWith(".jpg") && !name.endsWith(".png")) {
        throw new BusinessException("只支持 jpg/png");
    }
    // 3. 重命名，不用用户提供的文件名
    String newName = UUID.randomUUID() + name.substring(name.lastIndexOf("."));
    file.transferTo(new File("/uploads/" + newName));
    return "上传成功";
}
```
