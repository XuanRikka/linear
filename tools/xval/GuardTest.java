package org.linear;

import java.nio.file.Files;
import java.nio.file.Path;

/** parseC2meToml 边界测试。 */
public class GuardTest {
    static int fails = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("c2metoml");

        check("标准 false", write(dir, "[ioSystem]\nreplaceImpl = false\n"),
                "ioSystem.replaceImpl", true, Boolean.FALSE);
        check("标准 true", write(dir, "[ioSystem]\nreplaceImpl = true\n"),
                "ioSystem.replaceImpl", true, Boolean.TRUE);
        check("文件不存在按默认 true", dir.resolve("nope.toml"),
                "ioSystem.replaceImpl", true, Boolean.TRUE);
        check("文件不存在按默认 false", dir.resolve("nope.toml"),
                "ioSystem.gcFreeChunkSerializer", false, Boolean.FALSE);
        check("键缺失按默认", write(dir, "[ioSystem]\nsomethingElse = 1\n"),
                "ioSystem.replaceImpl", true, Boolean.TRUE);
        check("行尾注释", write(dir, "[ioSystem]\nreplaceImpl = false # disabled by user\n"),
                "ioSystem.replaceImpl", true, Boolean.FALSE);
        check("同名键在别的 section 不干扰", write(dir,
                "[other]\nreplaceImpl = true\n[ioSystem]\nreplaceImpl = false\n"),
                "ioSystem.replaceImpl", true, Boolean.FALSE);
        check("带引号的键", write(dir, "[ioSystem]\n\"replaceImpl\" = false\n"),
                "ioSystem.replaceImpl", true, Boolean.FALSE);
        check("非布尔值返回 null", write(dir, "[ioSystem]\nreplaceImpl = \"maybe\"\n"),
                "ioSystem.replaceImpl", true, null);
        check("两键同文件各取各的", write(dir,
                "[ioSystem]\nreplaceImpl = false\ngcFreeChunkSerializer = false\n"),
                "ioSystem.gcFreeChunkSerializer", true, Boolean.FALSE);
        check("值里带 # 的字符串不误切注释", write(dir,
                "[ioSystem]\nname = \"a#b\"\nreplaceImpl = false\n"),
                "ioSystem.replaceImpl", true, Boolean.FALSE);
        check("大小写布尔（TOML 规范只认小写，True 视为无法确认）", write(dir,
                "[ioSystem]\nreplaceImpl = True\n"),
                "ioSystem.replaceImpl", true, null);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    static Path write(Path dir, String content) throws Exception {
        Path f = Files.createTempFile(dir, "c2me", ".toml");
        Files.writeString(f, content);
        return f;
    }

    static void check(String name, Path file, String key, boolean def, Boolean expected) {
        Boolean got = Linear.parseC2meToml(file, key, def);
        boolean ok = expected == null ? got == null : expected.equals(got);
        if (!ok) {
            fails++;
            System.out.println("FAIL [" + name + "] expected=" + expected + " got=" + got);
        } else {
            System.out.println("ok   [" + name + "] -> " + got);
        }
    }
}
