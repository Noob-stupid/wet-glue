import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 胶水示例：索引查找到底该用红黑树还是 B+ 树？
 *
 * 这块拿不准，于是用头尾两行注释框起来 —— 中间这一块就是"胶水"。
 * 两个候选实现都登记在 .glue/lookup/ 下，用 glue use 切换，
 * 切换直接改源码文本，运行时没有任何间接层。
 */
public class IndexStore {

    // 候选一：红黑树（JDK TreeMap）
    private final TreeMap<Integer, String> rbtree = new TreeMap<>();

    // 候选二：B+ 树（此处简化为有序数组 + 二分，只为演示切换流程）
    private final ArrayList<Integer> bpKeys = new ArrayList<>();
    private final ArrayList<String> bpVals = new ArrayList<>();

    public void put(int key, String val) {
        rbtree.put(key, val);
        int i = Collections.binarySearch(bpKeys, key);
        if (i >= 0) bpVals.set(i, val);
        else { bpKeys.add(-i - 1, key); bpVals.add(-i - 1, val); }
    }

    public String get(int key) {
        // glue:begin lookup
        int found = Collections.binarySearch(bpKeys, key);
        return (found < 0) ? null : bpVals.get(found);
        // glue:end
    }

    public int size() {
        return rbtree.size();
    }

    public static void main(String[] args) {
        IndexStore s = new IndexStore();
        int n = 200_000;
        for (int i = 0; i < n; i++) s.put(i, "v" + i);

        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            long acc = 0;
            for (int i = 0; i < n; i++) if (s.get(i) != null) acc++;
            long t1 = System.nanoTime();
            best = Math.min(best, t1 - t0);
            if (acc != n) throw new IllegalStateException("结果不一致: " + acc);
        }
        System.out.printf("20 万次查找，最快一轮: %.2f ms%n", best / 1e6);
    }
}
