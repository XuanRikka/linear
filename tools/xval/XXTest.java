import org.linear.storage.util.XXHash32;
import org.linear.storage.util.XXHash64;

/** 打印各测试向量的哈希值，供与 Python xxhash 对拍。 */
public class XXTest {
    public static void main(String[] args) {
        // 覆盖：空输入、<4、<8、<16（32位分支）、<32（64位分支）、跨块长输入、种子 0 与 0x0721
        int[] lens = {0, 1, 3, 4, 7, 8, 13, 15, 16, 17, 31, 32, 33, 63, 64, 100, 1000, 65537};
        long[] seeds = {0L, 0x0721L, 0x9E3779B1L, -1L};
        for (int len : lens) {
            byte[] data = new byte[len];
            // 确定性伪随机填充（与 Python 侧公式一致）
            for (int i = 0; i < len; i++) {
                data[i] = (byte) ((i * 31 + 7) & 0xFF);
            }
            for (long seed : seeds) {
                int h32 = XXHash32.hash(data, (int) seed);
                long h64 = XXHash64.hash(data, seed);
                System.out.printf("%d %d %08x %016x%n", len, seed, h32, h64);
            }
        }
        // 带偏移的重载也测一下：数据中段
        byte[] big = new byte[256];
        for (int i = 0; i < 256; i++) big[i] = (byte) ((i * 31 + 7) & 0xFF);
        System.out.printf("OFF %08x %016x%n",
                XXHash32.hash(big, 10, 100, 0x0721),
                XXHash64.hash(big, 10, 100, 0x0721L));
    }
}
