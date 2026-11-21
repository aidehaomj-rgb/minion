package com.minion.core.tools.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.*;

/** §4.8 TOFU known_hosts 验收：三态、pending 捕获、持久化重载、非 22 端口别名、指纹/blob 解析 */
public class SshKnownHostsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 构造形如 SSH 公钥 blob：4 字节长度 + 算法名 + 主体 */
    private static byte[] blob(String type, byte[] body) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[4 + t.length + body.length];
        out[0] = (byte) (t.length >>> 24); out[1] = (byte) (t.length >>> 16);
        out[2] = (byte) (t.length >>> 8); out[3] = (byte) t.length;
        System.arraycopy(t, 0, out, 4, t.length);
        System.arraycopy(body, 0, out, 4 + t.length, body.length);
        return out;
    }

    @Test public void checkThreeStatesAndPendingCapture() throws Exception {
        SshKnownHosts repo = new SshKnownHosts(tmp.newFolder().toPath().resolve("kh"));
        byte[] key = blob("ssh-rsa", new byte[]{1, 2, 3});
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("srv", key));
        assertNotNull(repo.pendingKey());                                    // 待确认指纹已捕获
        assertEquals("srv", repo.pendingKey().getHost());
        assertEquals("ssh-rsa", repo.pendingKey().getType());
        assertTrue(repo.lastCheckWas("srv", HostKeyRepository.NOT_INCLUDED));
        // 记录后同键 OK；不同键 CHANGED
        repo.add(repo.pendingKey(), null);
        assertEquals(HostKeyRepository.OK, repo.check("srv", key));
        assertEquals(HostKeyRepository.CHANGED, repo.check("srv", blob("ssh-rsa", new byte[]{9, 9})));
        assertTrue(repo.lastCheckWas("srv", HostKeyRepository.CHANGED));
        // 其他主机 NOT_INCLUDED，且 lastCheck 守卫不再指向 srv
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("other", key));
        assertFalse(repo.lastCheckWas("srv", HostKeyRepository.CHANGED));
    }

    @Test public void persistAndReloadAcrossInstances() throws Exception {
        Path file = tmp.newFolder().toPath().resolve("kh");
        SshKnownHosts r1 = new SshKnownHosts(file);
        byte[] key = blob("ssh-ed25519", new byte[]{7, 7, 7});
        r1.check("srv", key);
        r1.add(r1.pendingKey(), null);
        assertTrue(Files.exists(file));
        SshKnownHosts r2 = new SshKnownHosts(file);
        assertEquals(HostKeyRepository.OK, r2.check("srv", key));
        assertEquals(1, r2.getHostKey().length);
        assertEquals("ssh-ed25519", r2.getHostKey()[0].getType());
        // remove 后回到 NOT_INCLUDED
        r2.remove("srv", "ssh-ed25519");
        assertEquals(HostKeyRepository.NOT_INCLUDED, r2.check("srv", key));
    }

    /** 非 22 端口别名：同主机不同端口不混淆 */
    @Test public void aliasKeepsPortsDistinct() throws Exception {
        SshKnownHosts repo = new SshKnownHosts(tmp.newFolder().toPath().resolve("kh"));
        byte[] k2222 = blob("ssh-rsa", new byte[]{1});
        byte[] k22 = blob("ssh-rsa", new byte[]{2});
        repo.setAlias("srv", "srv:2222");
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("srv", k2222));
        repo.add(repo.pendingKey(), null);                    // 记为 srv:2222
        repo.setAlias(null, null);
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("srv", k22));   // 22 端口另一键，独立
        repo.setAlias("srv", "srv:2222");
        assertEquals(HostKeyRepository.OK, repo.check("srv", k2222));
        assertEquals("srv:2222", repo.getHostKey()[0].getHost());
    }

    /** 指纹口径：SHA256: base64（OpenSSH 展示形式）；keyTypeOf 从 blob 首字符串解析 */
    @Test public void fingerprintAndTypeParsing() {
        byte[] key = blob("ecdsa-sha2-nistp256", new byte[]{5, 5});
        assertEquals("ecdsa-sha2-nistp256", SshKnownHosts.keyTypeOf(key));
        String fp = SshKnownHosts.fingerprint(key);
        assertTrue(fp.startsWith("SHA256:"));
        assertFalse(fp.endsWith("="));   // 尾部 padding 去除（OpenSSH 口径）
        assertEquals(fp, SshKnownHosts.fingerprint(key));   // 稳定
        // 损坏 blob → unknown（fail-closed 不炸）
        assertEquals("unknown", SshKnownHosts.keyTypeOf(new byte[]{1, 2}));
    }

    /** 坏行文件：跳过不炸，好行仍可用 */
    @Test public void corruptLinesSkipped() throws Exception {
        Path file = tmp.newFolder().toPath().resolve("kh");
        byte[] key = blob("ssh-rsa", new byte[]{4});
        Files.write(file, ("garbage-line\n"
                + "srv|ssh-rsa|" + Base64.getEncoder().encodeToString(key) + "\n"
                + "srv|ssh-rsa|!!!not-base64!!!\n").getBytes(StandardCharsets.UTF_8));
        SshKnownHosts repo = new SshKnownHosts(file);
        assertEquals(1, repo.getHostKey().length);
        assertEquals(HostKeyRepository.OK, repo.check("srv", key));
    }
}
