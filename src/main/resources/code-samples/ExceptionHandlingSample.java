package sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异常处理正确示例
 */
public class ExceptionHandlingSample {

    private static final Logger log = LoggerFactory.getLogger(ExceptionHandlingSample.class);

    /**
     * 正确：catch 具体异常 + 记录日志 + 抛出
     */
    public void handleException(String input) {
        try {
            process(input);
        } catch (IllegalArgumentException e) {
            log.error("参数非法, input={}", input, e);
            throw new BusinessException("参数非法: " + input, e);
        }
    }

    /**
     * 正确：try-with-resources 自动关闭资源
     */
    public String readFile(String path) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            return reader.readLine();
        } catch (java.io.IOException e) {
            log.error("读取文件失败, path={}", path, e);
            throw new RuntimeException("读取失败", e);
        }
    }

    private void process(String input) {
        if (input == null) {
            throw new IllegalArgumentException("input 不能为空");
        }
        System.out.println(input);
    }

    static class BusinessException extends RuntimeException {
        public BusinessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
