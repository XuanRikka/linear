# 与 Java 输出对拍：逐行重算并比较
import xxhash, sys

fails = 0
for line in open("java_hashes.txt"):
    parts = line.split()
    if parts[0] == "OFF":
        data = bytes((i * 31 + 7) & 0xFF for i in range(256))[10:110]
        h32 = xxhash.xxh32_intdigest(data, 0x0721)
        h64 = xxhash.xxh64_intdigest(data, 0x0721)
        ok = f"{h32:08x}" == parts[1] and f"{h64:016x}" == parts[2]
        if not ok:
            fails += 1
            print("FAIL OFF", parts, f"{h32:08x}", f"{h64:016x}")
        continue
    length, seed = int(parts[0]), int(parts[1])
    data = bytes((i * 31 + 7) & 0xFF for i in range(length))
    # Java 的 int seed 是截断后的 32 位；python 需要无符号
    seed32 = seed & 0xFFFFFFFF
    seed64 = seed & 0xFFFFFFFFFFFFFFFF
    h32 = xxhash.xxh32_intdigest(data, seed32)
    h64 = xxhash.xxh64_intdigest(data, seed64)
    if f"{h32:08x}" != parts[2] or f"{h64:016x}" != parts[3]:
        fails += 1
        print("FAIL", parts, "->", f"{h32:08x}", f"{h64:016x}")

print("ALL PASS" if fails == 0 else f"{fails} FAILURES")
sys.exit(1 if fails else 0)
