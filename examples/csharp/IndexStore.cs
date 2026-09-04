// 胶水 C# 示例：同一个工具、同一套头尾标记，在 C# 里也能用。
// 证明「引擎逻辑与被管语言无关」——C# 用的就是与 Java 相同的 // 行注释标记。
// 候选一：SortedDictionary（红黑树）  候选二：有序 List + 二分（B+ 树简化）

using System;
using System.Collections.Generic;

class IndexStore
{
    // 候选一：红黑树
    private readonly SortedDictionary<int, string> rb = new();

    // 候选二：B+ 树（简化：有序 List + 二分）
    private readonly List<int> bpKeys = new();
    private readonly List<string> bpVals = new();

    public void Put(int key, string val)
    {
        rb[key] = val;
        int i = bpKeys.BinarySearch(key);
        if (i >= 0) bpVals[i] = val;
        else { bpKeys.Insert(~i, key); bpVals.Insert(~i, val); }
    }

    public string? Get(int key)
    {
        // glue:begin cs_lookup
        return rb.TryGetValue(key, out var v) ? v : null;
        // glue:end
    }

    public static void Main()
    {
        var s = new IndexStore();
        int n = 200_000;
        for (int i = 0; i < n; i++) s.Put(i, "v" + i);

        long best = long.MaxValue;
        for (int round = 0; round < 5; round++)
        {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            long acc = 0;
            for (int i = 0; i < n; i++) if (s.Get(i) != null) acc++;
            long t1 = System.Diagnostics.Stopwatch.GetTimestamp();
            long ms = (t1 - t0) * 1000 / System.Diagnostics.Stopwatch.Frequency;
            if (ms < best) best = ms;
            if (acc != n) throw new Exception("结果不一致: " + acc);
        }
        Console.WriteLine($"20 万次查找，最快一轮: {best} ms");
    }
}
