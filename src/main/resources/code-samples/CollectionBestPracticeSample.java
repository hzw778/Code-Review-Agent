package sample;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 集合使用最佳实践示例
 *
 * <p>演示 ArrayList、HashMap、Stream 的正确用法，
 * 每个方法对应一条编码规范，作为代码 RAG 的检索样本。
 */
public class CollectionBestPracticeSample {

    /**
     * 正确：返回空集合而非 null
     *
     * <p>调用方无需判 null，避免 NPE。
     */
    public List<String> findKeywords(boolean found) {
        if (!found) {
            return Collections.emptyList();
        }
        return Arrays.asList("java", "spring", "jpa");
    }

    /**
     * 正确：指定集合初始容量
     *
     * <p>预知大小时指定容量，避免频繁扩容。
     */
    public List<Integer> buildRange(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * 正确：HashMap 指定初始容量
     *
     * <p>HashMap 容量 = 预期元素数 / 0.75 + 1，避免扩容。
     */
    public Map<String, Integer> buildFreqMap(List<String> words) {
        int capacity = (int) (words.size() / 0.75f) + 1;
        Map<String, Integer> freq = new HashMap<>(capacity);
        for (String w : words) {
            freq.merge(w, 1, Integer::sum);
        }
        return freq;
    }

    /**
     * 正确：使用 removeIf 删除元素
     *
     * <p>避免 for-each 中修改集合导致 ConcurrentModificationException。
     */
    public void removeInvalid(List<String> list) {
        list.removeIf(s -> s == null || s.isBlank());
    }

    /**
     * 正确：toMap 指定合并函数
     *
     * <p>避免重复 key 抛 IllegalStateException。
     */
    public Map<String, Integer> nameToAge(List<User> users) {
        return users.stream()
                .collect(Collectors.toMap(
                        User::getName,
                        User::getAge,
                        (a, b) -> a));
    }

    /**
     * 正确：用 flatMap 展平嵌套集合
     */
    public List<String> flattenOrders(List<Order> orders) {
        return orders.stream()
                .flatMap(o -> o.getItems().stream())
                .map(Item::getName)
                .collect(Collectors.toList());
    }

    /**
     * 正确：用 groupingBy 分组
     */
    public Map<String, List<User>> groupByDept(List<User> users) {
        return users.stream()
                .collect(Collectors.groupingBy(User::getDept));
    }

    /**
     * 正确：用不可变集合
     *
     * <p>防止调用方修改内部状态。
     */
    public List<String> getKeywords() {
        List<String> keywords = Arrays.asList("java", "spring", "jpa");
        return Collections.unmodifiableList(keywords);
    }

    /** 频繁查找用 Set 而非 List */
    public boolean containsAny(List<String> source, List<String> targets) {
        Set<String> set = new HashSet<>(source);
        for (String t : targets) {
            if (set.contains(t)) return true;
        }
        return false;
    }

    // ===== 内部类 =====
    static class User {
        private String name;
        private Integer age;
        private String dept;

        public String getName() { return name; }
        public Integer getAge() { return age; }
        public String getDept() { return dept; }
    }

    static class Order {
        private List<Item> items;
        public List<Item> getItems() { return items; }
    }

    static class Item {
        private String name;
        public String getName() { return name; }
    }
}
