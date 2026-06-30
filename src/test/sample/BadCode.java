package sample;

/**
 * 测试用 Java 文件（包含多种 AST 问题）
 */
public class BadCode {

    // 1. 命名规范问题：常量应该用 UPPER_SNAKE_CASE（但本规则不检测常量）
    private static final int MAX_RETRY = 3;

    // 2. 命名规范问题：变量名不符合驼峰
    private int user_count = 0;  // 应该是 userCount

    // 3. 类名规范（这个类名 BadCode 是正确的）

    public void emptyCatchExample() {
        try {
            riskyOperation();
        } catch (Exception e) {
            // 空的 catch 块，问题：EMPTY_CATCH
        }
    }

    public void nestedEmptyCatch() {
        try {
            riskyOperation();
        } catch (Exception e) {
            try {
                // 内层空 catch，问题：EMPTY_CATCH
            } catch (Exception ex) {
                // 这里也是空，问题：EMPTY_CATCH
            }
        }
    }

    public void normalCatch() {
        try {
            riskyOperation();
        } catch (Exception e) {
            System.out.println("错误：" + e.getMessage());
        }
    }

    public void magicNumberExample() {
        int timeout = 5000;  // 魔法数字：5000
        int retryCount = 3;  // 不算魔法数字：3 不在常用值里？等等，3 也不在 0/1/2 里！
        int zero = 0;        // 不算魔法数字：0 在常用值里
        int one = 1;         // 不算魔法数字：1 在常用值里
    }

    public void resourceLeakExample() throws Exception {
        // 资源泄漏：直接 new FileInputStream 但没用 try-with-resources
        java.io.FileInputStream fis = new java.io.FileInputStream("test.txt");
        // 没有 close，问题：RESOURCE_LEAK
    }

    public void resourceSafeExample() throws Exception {
        // 安全用法：try-with-resources
        try (java.io.FileInputStream fis = new java.io.FileInputStream("test.txt")) {
            // 使用 fis
        }
    }

    public void namingConventionExample() {
        int BadName = 1;  // 变量名首字母大写，问题：NAMING_CONVENTION
        String URL = "http://...";  // 变量名全大写，问题：NAMING_CONVENTION
    }

    public void LongMethodExample() {
        // 故意写一个超长方法（30+ 语句）触发 METHOD_TOO_LONG
        int a1 = 1;
        int a2 = 1;
        int a3 = 1;
        int a4 = 1;
        int a5 = 1;
        int a6 = 1;
        int a7 = 1;
        int a8 = 1;
        int a9 = 1;
        int a10 = 1;
        int a11 = 1;
        int a12 = 1;
        int a13 = 1;
        int a14 = 1;
        int a15 = 1;
        int a16 = 1;
        int a17 = 1;
        int a18 = 1;
        int a19 = 1;
        int a20 = 1;
        int a21 = 1;
        int a22 = 1;
        int a23 = 1;
        int a24 = 1;
        int a25 = 1;
        int a26 = 1;
        int a27 = 1;
        int a28 = 1;
        int a29 = 1;
        int a30 = 1;
        int a31 = 1;
        int a32 = 1;  // 第 32 条语句，超过 30 阈值
    }

    private void riskyOperation() {
        throw new RuntimeException("故意抛异常");
    }
}