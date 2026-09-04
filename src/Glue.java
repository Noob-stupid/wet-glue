import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * 胶水 glue —— 可替换代码块的最小可用实现（MVP, Java 17）
 *
 * 标记语法（就是头尾两行注释，中间那块即胶水）：
 *
 *     // glue:begin lookup
 *     ... 拿不准的代码 ...
 *     // glue:end
 *
 * 命令：
 *     scan                     扫描源码，登记所有胶水区域
 *     list                     列出区域、状态，以及「胶水干了没」
 *     add  <name> <alias>      把区域当前内容另存为一个候选实现
 *     use  <name> <alias>      切换到某个候选实现（源码替换）
 *     refs <name>              查看这块代码对外部的隐式契约
 *     seal <name>              固化：焊死代码，移除标记
 *     unseal <name>            解固化：重新插入标记
 *     check                    校验区域是否漂移，供 CI 使用
 */
public class Glue {

    static final String HEAD = "// glue:begin ";
    static final String TAIL = "// glue:end";
    static final Pattern NAME_OK = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * 被管语言的扩展名（v1 架构预留）：凡行注释是「//」的语言，标记与引擎逻辑完全一致，
     * 接入新语言只需把它的扩展名加进这个集合 —— 无需改动引擎。C 家族语言：
     * .java 之外还有 C#/Kotlin/Go/TS/JS/C/C++/Rust 等。
     * （行注释是「#」的语言如 Python/Shell 需改标记约定，属 v2，本集合不含。）
     */
    static final Set<String> SRC_EXT = Set.of(
            ".java", ".cs", ".kt", ".kts", ".go", ".ts", ".tsx", ".js", ".jsx",
            ".c", ".cpp", ".h", ".hpp", ".rs", ".swift");
    static String extOf(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i).toLowerCase();
    }
    static final Path ROOT = Paths.get("").toAbsolutePath();
    static final Path GLUE_DIR = ROOT.resolve(".glue");
    static final Path SEALED_DIR = GLUE_DIR.resolve("_sealed");
    static final Path LOCK = ROOT.resolve("swappable.lock");
    static final int DRY_DAYS = 60;
    static final int STIFF_DAYS = 30;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { help(); return; }
        String cmd = args[0];
        switch (cmd) {
            case "scan"   -> cmdScan();
            case "list"   -> cmdList();
            case "add"    -> cmdAdd(arg(args, 1, "区域名"), arg(args, 2, "候选别名"));
            case "use"    -> cmdUse(arg(args, 1, "区域名"), arg(args, 2, "候选别名"));
            case "switch" -> cmdUse(arg(args, 1, "区域名"), arg(args, 2, "候选别名"));
            case "refs"   -> cmdRefs(arg(args, 1, "区域名"));
            case "seal"   -> cmdSeal(arg(args, 1, "区域名"));
            case "unseal" -> cmdUnseal(arg(args, 1, "区域名"));
            case "verify" -> cmdVerify(args);
            case "check"  -> cmdCheck();
            default       -> { System.out.println("未知命令: " + cmd); help(); }
        }
    }

    static String arg(String[] a, int i, String what) {
        if (a.length <= i) throw new IllegalArgumentException("缺少参数: " + what);
        return a[i];
    }

    static void help() {
        System.out.println("""
            胶水 glue —— 管理「拿不准的代码」

              scan                    扫描源码，登记所有胶水区域
              list                    列出区域、状态，以及「胶水干了没」
              add  <name> <alias>     把区域当前内容另存为一个候选实现
              use  <name> <alias>     切换到某个候选实现
              refs <name>             查看这块代码对外部的隐式契约
              seal <name>             固化：焊死代码，移除头尾标记
              unseal <name>           解固化：重新插入标记
              verify <name> [命令]    登记/执行该区域的验证命令（切换后一键跑测试）
              check                   校验区域漂移（CI 用，有漂移则退出码 1）

            标记写法：
              // glue:begin <name>
                  ... 拿不准的代码 ...
              // glue:end
            """);
    }

    // ----------------------------------------------------------------- 模型

    record Region(String name, Path file, int beginIdx, int endIdx, List<String> body) {
        String indent() {
            return body.isEmpty() ? "" : leading(body.get(0));
        }
        Path rel() { return ROOT.relativize(file); }
    }

    // ------------------------------------------------------------- 扫描源码

    static List<Region> scan() throws Exception {
        List<Region> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(ROOT)) {
            files = s.filter(Files::isRegularFile)
                     .filter(p -> SRC_EXT.contains(extOf(p)))
                     .filter(Glue::notIgnored)
                     .filter(p -> !p.getFileName().toString().equals("Glue.java")) // 跳过工具本体
                     .sorted()
                     .toList();
        }
        for (Path p : files) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            String name = null;
            int begin = -1;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).strip();
                if (name == null && t.startsWith(HEAD)) {
                    String cand = t.substring(HEAD.length()).strip();
                    if (!NAME_OK.matcher(cand).matches()) continue;         // 形如 <name> 的是文档示例，不是真标记
                    name = cand;
                    begin = i;
                } else if (name != null && t.equals(TAIL)) {
                    out.add(new Region(name, p, begin, i, List.copyOf(lines.subList(begin + 1, i))));
                    name = null;
                }
            }
        }
        return out;
    }

    static boolean notIgnored(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String d : List.of("/.glue/", "/target/", "/build/", "/.git/", "/out/", "/bin/", "/obj/", "/node_modules/"))
            if (s.contains(d)) return false;
        return true;
    }

    static Region find(String name) throws Exception {
        List<Region> hit = new ArrayList<>();
        for (Region r : scan()) if (r.name().equals(name)) hit.add(r);
        if (hit.isEmpty()) throw notFound(name);
        if (hit.size() > 1) throw new IllegalStateException("区域名重复: " + name + "，出现在 " + hit.size() + " 处");
        return hit.get(0);
    }

    /** 源码里找不到区域时的带引导的诊断，而不是干巴巴一句"找不到"。 */
    static NoSuchElementException notFound(String name) throws Exception {
        Map<String, String> m = loadMeta(name);
        if (!m.isEmpty()) {
            String status = m.getOrDefault("status", "?");
            if ("sealed".equals(status))
                return new NoSuchElementException("区域 " + name + " 已固化（sealed），标记已移除。\n"
                        + "  若要改动/切换：先 unseal 解固化 → use 切换 → 重跑测试 → 再 seal。\n"
                        + "  固化记录在 .glue/" + name + "，可 unseal 还原。");
            return new NoSuchElementException("区域 " + name + " 曾登记过但源码里找不到它的标记，可能是标记被误删。\n"
                    + "  若想恢复：用 unseal 重新插入标记，或检查 git diff 确认改动。");
        }
        return new NoSuchElementException("源码里找不到胶水区域: " + name + "（先运行 scan 确认，或用 add 登记）");
    }

    // --------------------------------------------------------------- 元数据

    static Path dirOf(String name)        { return GLUE_DIR.resolve(name); }
    static Path sealedDirOf(String name)  { return SEALED_DIR.resolve(name); }
    static Path metaOf(String name)       { return dirOf(name).resolve("glue.properties"); }
    static Path fragOf(String name, String alias) { return dirOf(name).resolve(alias + ".frag"); }

    static Map<String, String> loadMeta(String name) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        Path f = metaOf(name);
        if (Files.exists(f))
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i > 0 && !line.startsWith("#")) m.put(line.substring(0, i).strip(), line.substring(i + 1).strip());
            }
        return m;
    }

    static void saveMeta(String name, Map<String, String> m) throws Exception {
        Files.createDirectories(dirOf(name));
        StringBuilder sb = new StringBuilder();
        for (String k : new TreeSet<>(m.keySet())) sb.append(k).append('=').append(m.get(k)).append('\n');
        Files.writeString(metaOf(name), sb.toString(), StandardCharsets.UTF_8);
    }

    static List<String> candidates(Map<String, String> m) {
        String c = m.getOrDefault("candidates", "");
        return c.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(c.split(",")));
    }

    // ----------------------------------------------------------------- 命令

    static void cmdScan() throws Exception {
        List<Region> rs = scan();
        if (rs.isEmpty()) {
            System.out.println("没有发现胶水区域。在源码里加上：");
            System.out.println("  " + HEAD + "<name>");
            System.out.println("  ... 拿不准的代码 ...");
            System.out.println("  " + TAIL);
            return;
        }
        System.out.println("发现 " + rs.size() + " 个胶水区域：\n");
        for (Region r : rs) {
            Map<String, String> m = loadMeta(r.name());
            boolean isNew = m.isEmpty();
            if (isNew) {
                m = new LinkedHashMap<>();
                m.put("name", r.name());
                m.put("file", r.rel().toString().replace('\\', '/'));
                m.put("status", "experimental");
                m.put("selected", "current");
                m.put("candidates", "current");
                m.put("createdAt", LocalDate.now().toString());
            }
            m.put("lines", (r.beginIdx() + 1) + "-" + (r.endIdx() + 1));
            m.put("hash", hash(r.body()));
            refreshStatus(r.name(), m);
            saveMeta(r.name(), m);
            if (isNew) record(r.name(), "create", "status=experimental selected=" + m.get("selected"));
            Files.writeString(fragOf(r.name(), m.get("selected")), String.join("\n", dedent(r.body())) + "\n",
                    StandardCharsets.UTF_8);
            System.out.printf("  %-16s %-28s %s%n", r.name(), r.rel() + ":" + (r.beginIdx() + 1),
                    isNew ? "已登记（experimental）" : "已更新");
        }
        System.out.println("\n候选片段存放在 .glue/<name>/ 下。用 add 追加备选实现，用 use 切换。");
    }

    static void cmdList() throws Exception {
        List<Region> rs = scan();
        Map<String, Region> byName = new LinkedHashMap<>();
        for (Region r : rs) byName.put(r.name(), r);

        Set<String> known = new TreeSet<>();
        if (Files.isDirectory(GLUE_DIR))
            try (Stream<Path> s = Files.list(GLUE_DIR)) {
                for (Path p : s.filter(Files::isDirectory).toList()) known.add(p.getFileName().toString());
            }

        if (byName.isEmpty() && known.isEmpty()) { System.out.println("还没有任何胶水区域。先运行 scan。"); return; }

        System.out.printf("%n  %-14s %-9s %-11s %-22s %s%n", "区域", "状态", "当前实现", "候选", "胶水状态");
        System.out.println("  " + "-".repeat(78));
        for (Region r : rs) {
            Map<String, String> m = loadMeta(r.name());
            List<String> cand = candidates(m);
            System.out.printf("  %-14s %-9s %-11s %-22s %s%n",
                    r.name(), m.getOrDefault("status", "?"), m.getOrDefault("selected", "?"),
                    String.join(" | ", cand), dryness(m, cand));
        }
        Set<String> sealed = new TreeSet<>(known);
        sealed.removeAll(byName.keySet());
        sealed.remove("_sealed");
        for (String n : sealed) {
            Map<String, String> m = loadMeta(n);
            if ("sealed".equals(m.get("status")))
                System.out.printf("  %-14s %-9s %-11s %-22s %s%n", n, "sealed", m.getOrDefault("selected", "-"),
                        String.join(" | ", candidates(m)), "已固化（可用 unseal 解固化）");
        }
        System.out.println();
    }

    /** 「胶水干了没」—— 一块代码被标记为胶水，却长期无人决策，说明它要么该固化，要么当初不该标记。 */
    static String dryness(Map<String, String> m, List<String> cand) {
        if ("sealed".equals(m.get("status"))) return "已固化";
        String created = m.get("createdAt");
        if (created == null) return "-";
        long age = ChronoUnit.DAYS.between(LocalDate.parse(created), LocalDate.now());
        if (cand.size() <= 1)
            return age > STIFF_DAYS
                    ? "⚠ 标记 " + age + " 天仍无备选实现，当初可能不需要胶水"
                    : "只有单一实现（" + age + " 天）";
        String last = m.get("lastSwitchedAt");
        if (last == null) return "有 " + cand.size() + " 个候选，尚未切换过（" + age + " 天）";
        long d = ChronoUnit.DAYS.between(LocalDate.parse(last), LocalDate.now());
        if (d > DRY_DAYS) return "⚠ 胶水已干（" + d + " 天未切换），建议 seal 或 unseal 重新讨论";
        if (d > STIFF_DAYS) return "胶水开始变干（" + d + " 天未切换）";
        return "未干，保持可塑（" + d + " 天）";
    }

    static void cmdAdd(String name, String alias) throws Exception {
        Region r = find(name);
        Map<String, String> m = loadMeta(name);
        Path frag = fragOf(name, alias);
        if (Files.exists(frag)) System.out.println("覆盖已有候选: " + alias);
        Files.createDirectories(dirOf(name));
        Files.writeString(frag, String.join("\n", dedent(r.body())) + "\n", StandardCharsets.UTF_8);
        List<String> cand = candidates(m);
        if (!alias.equals("current")) {            // 一旦命名，临时占位 current 即失效
            cand.remove("current");
            Files.deleteIfExists(fragOf(name, "current"));
        }
        if (!cand.contains(alias)) cand.add(alias);
        m.put("candidates", String.join(",", cand));
        m.put("selected", alias);
        m.put("hash", hash(r.body()));
        refreshStatus(name, m);
        saveMeta(name, m);
        record(name, "add", "candidate=" + alias + " status=" + m.get("status"));
        System.out.println("已把区域 " + name + " 的当前内容登记为候选实现: " + alias);
        System.out.println("  片段: " + ROOT.relativize(frag));
    }

    static void cmdUse(String name, String alias) throws Exception {
        Region r = find(name);
        Map<String, String> m = loadMeta(name);
        Path frag = fragOf(name, alias);
        if (!Files.exists(frag))
            throw new NoSuchElementException("没有这个候选实现: " + alias + "（现有: " + String.join(", ", candidates(m)) + "）");

        String prevSelected = m.getOrDefault("selected", "?");
        // 切换前对比当前实现与目标实现的对外契约 —— 机械差异（改名/换依赖）一眼可见
        Path prevFrag = fragOf(name, prevSelected);
        if (!alias.equals(prevSelected) && Files.exists(prevFrag) && Files.exists(frag))
            compareContract(name, prevSelected, prevFrag, alias, frag);

        List<String> replacement = indent(
                Arrays.stream(Files.readString(frag, StandardCharsets.UTF_8).split("\n"))
                      .dropWhile(String::isBlank).toList(),
                r.indent());

        List<String> lines = new ArrayList<>(Files.readAllLines(r.file(), StandardCharsets.UTF_8));
        List<String> next = new ArrayList<>();
        next.addAll(lines.subList(0, r.beginIdx() + 1));
        next.addAll(replacement);
        next.addAll(lines.subList(r.endIdx(), lines.size()));
        Files.writeString(r.file(), String.join("\n", next) + "\n", StandardCharsets.UTF_8);

        m.put("selected", alias);
        m.put("lastSwitchedAt", LocalDate.now().toString());
        List<String> cand = candidates(m);
        if (!cand.contains(alias)) { cand.add(alias); m.put("candidates", String.join(",", cand)); }
        saveMeta(name, m);

        Region after = find(name);
        m.put("hash", hash(after.body()));
        m.put("lines", (after.beginIdx() + 1) + "-" + (after.endIdx() + 1));
        refreshStatus(name, m);
        saveMeta(name, m);
        record(name, "use", "from=" + prevSelected + " to=" + alias);

        System.out.println("区域 " + name + " 已切换到: " + alias);
        System.out.println("  " + r.rel() + ":" + (after.beginIdx() + 2) + " 起 " + replacement.size() + " 行已替换");
        System.out.println("  提示：切换只改源码文本，运行时没有任何间接层。");
        if (m.get("verifyCmd") != null)
            System.out.println("  跑一遍验证: glue verify " + name);
        else
            System.out.println("  跑一遍测试/基准确认行为没变。可先登记: glue verify " + name + " \"你的命令\"");
    }

    static void cmdRefs(String name) throws Exception {
        Region r = find(name);
        Map<String, String> m = loadMeta(name);
        Contract c = contractOf(r.body());

        System.out.println("\n胶水区域「" + name + "」的隐式契约（" + r.rel() + ":" + (r.beginIdx() + 2) + "）\n");
        System.out.println("  读取外部: " + (c.reads().isEmpty() ? "（无）" : String.join(", ", c.reads())));
        System.out.println("  写入外部: " + (c.writes().isEmpty() ? "（无）" : String.join(", ", c.writes())));
        System.out.println("  内部声明: " + (c.decls().isEmpty() ? "（无）" : String.join(", ", c.decls())));
        System.out.println("\n  候选实现: " + String.join(" | ", candidates(m)) + "   当前: " + m.get("selected"));
        System.out.println("  切换实现时，新实现必须保持相同的读写集合，否则行为会漂移。");
        System.out.println("  这份契约没有编译器保护 —— 这就是「未固化」的代价。固化（seal）即意味着你愿意承担它。\n");
    }

    // --------------------------------------------------- 契约对比（机械差异）

    record Contract(Set<String> reads, Set<String> writes, Set<String> decls) {}

    /** 对一段代码（区域体或候选片段）做隐式契约分析。 */
    static Contract contractOf(List<String> body) {
        Set<String> reads = new TreeSet<>(), writes = new TreeSet<>(), decls = new TreeSet<>();
        for (String line : body) analyseLine(stripComment(line), reads, writes, decls);
        reads.removeAll(decls);
        reads.removeAll(KEYWORDS);
        writes.removeAll(decls);      // 区域内声明并写入的变量属于内部细节，不算外部契约
        writes.removeAll(KEYWORDS);
        return new Contract(reads, writes, decls);
    }

    /** 读取候选片段文件并做契约分析。 */
    static Contract contractOfFrag(Path frag) throws Exception {
        List<String> lines = Arrays.stream(Files.readString(frag, StandardCharsets.UTF_8).split("\n")).toList();
        return contractOf(lines);
    }

    /**
     * 切换前对比两个候选的对外契约，把机械差异（改名、换依赖、参数/返回变化）亮出来。
     * 注意：这只是符号级提示 —— 真正的类型级校验由编译器（verify）把关。
     */
    static void compareContract(String name, String from, Path fromFrag, String to, Path toFrag) throws Exception {
        Contract a = contractOfFrag(fromFrag);
        Contract b = contractOfFrag(toFrag);
        Set<String> gainedR = new TreeSet<>(b.reads());  gainedR.removeAll(a.reads());
        Set<String> lostR   = new TreeSet<>(a.reads());  lostR.removeAll(b.reads());
        Set<String> gainedW = new TreeSet<>(b.writes()); gainedW.removeAll(a.writes());
        Set<String> lostW   = new TreeSet<>(a.writes()); lostW.removeAll(b.writes());
        if (gainedR.isEmpty() && lostR.isEmpty() && gainedW.isEmpty() && lostW.isEmpty()) return;

        System.out.println("\n  ⚠ 候选实现对外契约有差异（" + from + " → " + to + "）：");
        if (!gainedR.isEmpty()) System.out.println("    + 新增读取: " + String.join(", ", gainedR));
        if (!lostR.isEmpty())   System.out.println("    - 不再读取: " + String.join(", ", lostR));
        if (!gainedW.isEmpty()) System.out.println("    + 新增写入: " + String.join(", ", gainedW));
        if (!lostW.isEmpty())   System.out.println("    - 不再写入: " + String.join(", ", lostW));
        System.out.println("    切换后区域对外依赖的外部名字会变。若方法签名/返回类型也不同，");
        System.out.println("    请用 glue verify " + name + " 跑一遍编译 —— 编译器会精确告诉你差在哪。");
    }


    static void cmdSeal(String name) throws Exception {
        Region r = find(name);
        Map<String, String> m = loadMeta(name);
        if ("sealed".equals(m.get("status"))) { System.out.println("该区域已固化。"); return; }
        if (inCI() && !"1".equals(System.getenv("GLUE_ALLOW_SEAL"))) {
            System.out.println("拒绝执行：检测到 CI 环境。");
            System.out.println("v1.0 §4.2 规定「CI 中禁止自动执行固化」—— 固化必须由人工在本地完成。");
            System.out.println("（若确为人工触发的自动化测试，设 GLUE_ALLOW_SEAL=1 显式放行。）");
            System.exit(2);
        }

        List<String> cand = candidates(m);
        System.out.println("\n即将固化「" + name + "」");
        System.out.println("  位置: " + r.rel() + ":" + (r.beginIdx() + 1) + "-" + (r.endIdx() + 1));
        System.out.println("  当前实现: " + m.get("selected") + "    候选: " + String.join(" | ", cand));
        System.out.println("  固化后将移除头尾标记，代码焊死为普通代码，运行时开销严格为 0。");

        // v1.0 §4.2「固化 = 测试通过 + 人工确认」：登记过验证命令就先跑，失败禁止固化
        String vcmd = m.get("verifyCmd");
        if (vcmd != null) {
            System.out.println("  已登记验证命令，固化前先跑一遍…");
            int code = runShellCode(vcmd.replace("{root}", ROOT.toString()));
            if (code != 0) {
                System.out.println("\n✗ 验证失败（退出码 " + code + "），禁止固化。修复问题或确认改用例后再 seal。");
                System.exit(1);
            }
            System.out.println("  ✓ 验证通过。");
        } else {
            System.out.println("  ⚠ 未登记验证命令。建议先登记（glue verify " + name
                    + " \"编译+测试命令\"），否则固化无法自动确认正确性。");
        }

        if (!confirm("确认固化？")) { System.out.println("已取消。"); return; }

        Files.createDirectories(dirOf(name));
        Files.writeString(fragOf(name, m.get("selected")), String.join("\n", dedent(r.body())) + "\n",
                StandardCharsets.UTF_8);

        List<String> lines = new ArrayList<>(Files.readAllLines(r.file(), StandardCharsets.UTF_8));
        lines.remove(r.endIdx());
        lines.remove(r.beginIdx());
        Files.writeString(r.file(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        m.put("status", "sealed");
        m.put("sealedAt", LocalDate.now().toString());
        m.put("indent", r.indent());
        m.put("hash", hash(r.body()));
        saveMeta(name, m);
        record(name, "seal", "selected=" + m.get("selected") + " candidates=" + String.join("|", cand));
        System.out.println("\n已固化。标记已移除，归档在 " + ROOT.relativize(dirOf(name)) + "（unseal 可还原）。");
    }

    static void cmdUnseal(String name) throws Exception {
        Map<String, String> m = loadMeta(name);
        if (!"sealed".equals(m.get("status"))) { System.out.println("该区域未固化，无需解固化。"); return; }
        String file = m.get("file");
        if (file == null) throw new IllegalStateException("元数据缺少 file，无法还原");
        Path p = ROOT.resolve(file);

        // 固化只删了标记、代码留在原地，所以解固化要按内容定位那几行，再包上标记。
        List<String> frag = Arrays.stream(
                Files.readString(fragOf(name, m.getOrDefault("selected", "current")), StandardCharsets.UTF_8).split("\n"))
                .dropWhile(String::isBlank).toList();
        List<String> want = new ArrayList<>();
        for (String l : frag) if (!l.isBlank()) want.add(l.strip());
        if (want.isEmpty()) throw new IllegalStateException("片段为空，无法定位已固化的代码");

        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        int at = -1;
        for (int s = 0; s + want.size() <= lines.size() && at < 0; s++) {
            boolean ok = true;
            for (int k = 0; k < want.size(); k++)
                if (!lines.get(s + k).strip().equals(want.get(k))) { ok = false; break; }
            if (ok) at = s;
        }
        if (at < 0) throw new IllegalStateException(
                "在 " + file + " 中找不到已固化的那段代码，无法自动还原标记（可能固化后被改动过）。\n"
                + "        请手动加回：\n          " + HEAD + name + "\n          <原代码>\n          " + TAIL);

        String ind = leading(lines.get(at));
        List<String> block = new ArrayList<>();
        block.add(ind + HEAD + name);
        block.addAll(indent(frag, ind));
        block.add(ind + TAIL);

        List<String> next = new ArrayList<>();
        next.addAll(lines.subList(0, at));
        next.addAll(block);
        next.addAll(lines.subList(at + want.size(), lines.size()));
        Files.writeString(p, String.join("\n", next) + "\n", StandardCharsets.UTF_8);

        m.put("status", "experimental");
        m.put("lastSwitchedAt", LocalDate.now().toString());
        m.remove("sealedAt");
        refreshStatus(name, m);   // 若仍有多候选对比，应回到 candidate 而非 experimental
        saveMeta(name, m);
        record(name, "unseal", "restored=" + m.get("selected"));
        System.out.println("已解固化「" + name + "」，标记重新插入 " + ROOT.relativize(p) + ":" + (at + 1));
    }

    // --------------------------------------------- 一键验证（v1 增强：简单操作）

    static void cmdVerify(String[] args) throws Exception {
        String name = arg(args, 1, "区域名");
        if (args.length >= 3) {                    // glue verify <name> "命令" → 登记
            Map<String, String> m = loadMeta(name);
            String file = m.get("file");
            if (file == null) throw new IllegalStateException("区域未登记: " + name + "，先运行 scan");
            String cmd = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            m.put("verifyCmd", cmd);
            saveMeta(name, m);
            record(name, "verify", "register cmd=" + cmd);
            System.out.println("已为「" + name + "」登记验证命令:\n  " + cmd);
            System.out.println("之后用 glue verify " + name + " 一键执行。");
            return;
        }
        Map<String, String> m = loadMeta(name);
        String cmd = m.get("verifyCmd");
        if (cmd == null)
            throw new IllegalStateException("「" + name + "」还没有验证命令。先登记:\n  glue verify "
                    + name + " \"<编译+运行的命令>\"");
        runShell(cmd.replace("{root}", ROOT.toString()));
    }

    /** 执行一条 shell 命令，返回退出码（不退出 JVM）。 */
    static int runShellCode(String cmd) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] argv = win
                ? new String[]{ "cmd.exe", "/c", cmd }
                : new String[]{ "/bin/sh", "-c", cmd };
        System.out.println("\n$ " + cmd);
        Process p = new ProcessBuilder(argv).directory(ROOT.toFile()).inheritIO().start();
        return p.waitFor();
    }

    /** 透传地执行一条 shell 命令（支持 && 、; 等）。退出码原样返回。 */
    static void runShell(String cmd) throws Exception {
        int code = runShellCode(cmd);
        System.out.println(code == 0 ? "\n✓ 验证通过。" : "\n✗ 验证失败（退出码 " + code + "）。");
        System.exit(code);
    }

    static void cmdCheck() throws Exception {
        List<Region> rs = scan();
        Map<String, Region> byName = new LinkedHashMap<>();
        for (Region r : rs) byName.put(r.name(), r);

        int problems = 0;
        Set<String> known = new TreeSet<>();
        if (Files.isDirectory(GLUE_DIR))
            try (Stream<Path> s = Files.list(GLUE_DIR)) {
                for (Path p : s.filter(Files::isDirectory).toList()) known.add(p.getFileName().toString());
            }
        known.remove("_sealed");

        for (String n : known) {
            Map<String, String> m = loadMeta(n);
            if ("sealed".equals(m.get("status"))) continue;
            Region r = byName.get(n);
            if (r == null) {
                System.out.println("[缺失] " + n + " —— 源码里找不到标记，但 .glue 里还有记录");
                problems++;
                continue;
            }
            String recorded = m.get("hash");
            if (recorded != null && !recorded.equals(hash(r.body()))) {
                System.out.println("[漂移] " + n + " —— 区域内容已被直接修改，与候选片段不同步");
                System.out.println("       用 add " + n + " <新别名> 把它登记为新候选，或用 use " + n
                        + " " + m.get("selected") + " 回退到已登记版本");
                problems++;
            }
            String sel = m.get("selected");
            if (sel != null && !Files.exists(fragOf(n, sel))) {
                System.out.println("[缺片段] " + n + " —— 缺少当前实现的片段文件: " + sel);
                problems++;
            }
            List<String> leaked = leaks(r);
            if (!leaked.isEmpty()) {
                System.out.println("[胶水外泄] " + n + " —— 区域内部声明的名字被区域外引用，整块无法替换：");
                for (String lk : leaked) System.out.println("              " + lk);
                problems++;
            }
        }
        for (Region r : rs) {
            if (!known.contains(r.name())) {
                System.out.println("[未登记] " + r.name() + " —— 源码里有标记但尚未登记，运行 scan");
                problems++;
            }
        }
        System.out.println(problems == 0
                ? "\n检查通过：所有胶水区域与 .glue 记录一致。"
                : "\n发现 " + problems + " 个问题。");
        if (problems > 0) System.exit(1);
    }

    // --------------------------------------------------------- 隐式契约分析

    static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    static final Pattern DECL  = Pattern.compile("\\b(?:final\\s+)?[A-Za-z_$][\\w<>\\[\\],.\\s]*?\\s+([A-Za-z_$]\\w*)\\s*(?==[^=])");

    static void analyseLine(String line, Set<String> reads, Set<String> writes, Set<String> decls) {
        Matcher d = DECL.matcher(line);
        while (d.find()) decls.add(d.group(1));

        record Tok(String s, int start, int end) {}
        List<Tok> toks = new ArrayList<>();
        Matcher m = IDENT.matcher(line);
        while (m.find()) toks.add(new Tok(m.group(), m.start(), m.end()));

        for (Tok t : toks) {
            if (KEYWORDS.contains(t.s())) continue;
            char prev = t.start() > 0 ? line.charAt(t.start() - 1) : ' ';
            char next = t.end() < line.length() ? line.charAt(t.end()) : ' ';
            String next2 = t.end() + 1 < line.length() ? line.substring(t.end(), t.end() + 2) : "";
            if (next != '(' && prev != '.') reads.add(t.s());
            if (next2.equals("++") || next2.equals("--")) writes.add(t.s());
        }
        Matcher w = Pattern.compile("([A-Za-z_$]\\w*)\\s*(?:\\+\\+|--|\\+=|-=|=[^=])").matcher(line);
        while (w.find()) writes.add(w.group(1));
    }

    static final Set<String> KEYWORDS = Set.of(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
            "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
            "implements","import","instanceof","int","interface","long","native","new","package","private",
            "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while","var","yield","record","sealed",
            "true","false","null","String","Int","Integer","Long","Double","Boolean","List","Map","Set",
            "ArrayList","HashMap","TreeMap","Optional","Math","System","Objects","Collections");

    // ------------------------------------------------- v1.0 §4.2 / §4.4 合规

    /** v1.0 §4.2：所有状态变更记录到 swappable.lock，并纳入版本控制。 */
    static void record(String name, String action, String detail) throws Exception {
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String head = gitHead();
        Files.writeString(LOCK, ts + "  " + name + "  " + action + "  " + detail
                + (head != null ? "  git=" + head : "") + "\n",
                StandardCharsets.UTF_8,
                Files.exists(LOCK) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
    }

    static Boolean isGitRepo = null;

    /** 当前 git HEAD 短哈希；非 git 仓库返回 null（静默跳过，不打扰）。 */
    static String gitHead() {
        if (isGitRepo == null)
            isGitRepo = Files.isDirectory(ROOT.resolve(".git"));
        if (!isGitRepo) return null;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(ROOT.toFile())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() == 0 && !out.isEmpty()) return out;
        } catch (Exception ignored) { }
        return null;
    }

    /** v1.0 §3.2：有两个以上候选实现正在对比时，状态为 candidate，否则为 experimental。 */
    static void refreshStatus(String name, Map<String, String> m) {
        if ("sealed".equals(m.get("status"))) return;
        m.put("status", candidates(m).size() >= 2 ? "candidate" : "experimental");
    }

    /** v1.0 §4.2：CI 中禁止自动执行固化。 */
    static boolean inCI() {
        for (String k : List.of("CI", "GITHUB_ACTIONS", "GITLAB_CI", "JENKINS_URL", "BUILDKITE", "CIRCLECI")) {
            String v = System.getenv(k);
            if (v != null && !v.isBlank() && !v.equals("false")) return true;
        }
        return false;
    }

    /**
     * v1.0 §4.4 依赖违规检测在标记范式下的等价形式：
     * 胶水区域内部声明的名字，若在区域之外被引用，说明外部依赖了胶水的内部实现细节，
     * 这块代码就无法整体替换了 —— 也就是"胶水漏出来了"。
     */
    static List<String> leaks(Region r) throws Exception {
        Set<String> decls = new TreeSet<>();
        for (String line : r.body()) {
            Matcher d = DECL.matcher(stripComment(line));
            while (d.find()) decls.add(d.group(1));
        }
        List<String> lines = Files.readAllLines(r.file(), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String v : decls) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(v) + "\\b");
            for (int i = 0; i < lines.size(); i++) {
                if (i > r.beginIdx() && i < r.endIdx()) continue;
                if (p.matcher(stripComment(lines.get(i))).find()) {
                    out.add(v + "  →  被外部引用于 " + r.rel() + ":" + (i + 1));
                    break;
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- 工具

    static String stripComment(String s) {
        int i = s.indexOf("//");
        return i >= 0 ? s.substring(0, i) : s;
    }

    static String leading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    static List<String> dedent(List<String> lines) {
        String common = lines.stream().filter(l -> !l.isBlank())
                .map(Glue::leading).reduce(null, (a, b) -> a == null ? b : commonPrefix(a, b));
        int n = common == null ? 0 : common.length();
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(l.length() >= n ? l.substring(n) : l);
        return out;
    }

    static List<String> indent(List<String> lines, String pad) {
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(l.isBlank() ? l : pad + l);
        return out;
    }

    static String commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    static String hash(List<String> body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(String.join("\n", body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static boolean confirm(String prompt) throws Exception {
        System.out.print("\n" + prompt + " 输入 yes 继续: ");
        try (var sc = new java.util.Scanner(System.in)) {
            String a = sc.nextLine();
            return a != null && a.strip().equalsIgnoreCase("yes");
        }
    }
}
