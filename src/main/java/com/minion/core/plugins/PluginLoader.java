package com.minion.core.plugins;

import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.TrustedExecutable;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * §7.2 已启用插件装载：skills/ 目录 → Skill（SOURCE_PLUGIN，默认禁内联 shell）；
 * commands/*.md → 斜杠命令正文；agents/*.md → 子代理定义来源；hooks/MCP/LSP/statusLine
 * 声明 <b>必须先过 §10.9 信任复核</b>才可执行——未批准的声明不注册执行，仅在 /plugin
 * 展示「待信任」。禁用后全部能力完全消失（新会话/重载后不再装载）。
 */
public final class PluginLoader {

    /** 装载结果（会话合并用） */
    public static final class Runtime {
        public final List<Skill> skills = new ArrayList<Skill>();
        public final List<PluginCommand> commands = new ArrayList<PluginCommand>();
        /** 待信任（未过 §10.9 指纹复核）的执行声明清单——/plugin 展示用 */
        public final List<String> pendingTrust = new ArrayList<String>();
    }

    /** 插件斜杠命令（commands/<name>.md 正文展开为 prompt） */
    public static final class PluginCommand {
        public final String plugin;
        public final String name;
        public final String promptBody;

        PluginCommand(String plugin, String name, String promptBody) {
            this.plugin = plugin;
            this.name = name;
            this.promptBody = promptBody;
        }
    }

    private final PluginInstaller installer;
    private final ProjectTrustStore trustStore;

    public PluginLoader(PluginInstaller installer, ProjectTrustStore trustStore) {
        this.installer = installer;
        this.trustStore = trustStore;
    }

    /** 装载全部已启用插件（skills/commands 资料级能力 + 信任复核清单） */
    public Runtime loadEnabled() {
        Runtime out = new Runtime();
        for (PluginInstaller.Installed installed : installer.installed()) {
            if (!installed.enabled) continue;   // 禁用=完全消失
            Path dir = installed.cacheDir;
            if (!Files.isDirectory(dir)) continue;
            loadSkills(installed, dir, out);
            loadCommands(installed, dir, out);
            collectPendingTrust(installed, dir, out);
        }
        return out;
    }

    private void loadSkills(PluginInstaller.Installed installed, Path dir, Runtime out) {
        Path skills = dir.resolve("skills");
        if (!Files.isDirectory(skills)) return;
        SkillManager.ScanResult scan = SkillManager.scanTree(skills, 4, 64);
        for (Skill s : scan.skills)
            out.skills.add(new Skill(s.name, s.description, s.instructions, s.file,
                    Skill.SOURCE_PLUGIN, s.whenToUse, s.allowedTools, s.argumentHint,
                    s.arguments, s.model, s.version, s.disableModelInvocation, s.userInvocable,
                    s.context, s.agent, s.paths));
    }

    private void loadCommands(PluginInstaller.Installed installed, Path dir, Runtime out) {
        Path commands = dir.resolve("commands");
        if (!Files.isDirectory(commands)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(commands, "*.md")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                name = name.substring(0, name.length() - 3);
                String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                out.commands.add(new PluginCommand(installed.name, name, body));
            }
        } catch (Exception ignored) {
        }
    }

    /** hooks/MCP/LSP/statusLine 声明逐条 §10.9 指纹复核——未批准进 pendingTrust（不执行） */
    private void collectPendingTrust(PluginInstaller.Installed installed, Path dir, Runtime out) {
        try {
            PluginManifest m = PluginManifest.load(dir);
            for (String command : m.hookCommands)
                checkOrPending(out, "plugin", installed.name + " hook", command, dir);
            for (java.util.Map.Entry<String, com.google.gson.JsonObject> e : m.mcpServers.entrySet()) {
                com.google.gson.JsonObject server = e.getValue();
                String command = server.has("command") ? server.get("command").getAsString() : "";
                if (!command.isEmpty())
                    checkOrPending(out, "plugin", installed.name + " mcp:" + e.getKey(), command, dir);
            }
            for (java.util.Map.Entry<String, String> e : m.lspServers.entrySet())
                checkOrPending(out, "plugin", installed.name + " lsp:" + e.getKey(), e.getValue(), dir);
            if (m.statusLine != null && !m.statusLine.isEmpty())
                checkOrPending(out, "plugin", installed.name + " statusLine", m.statusLine, dir);
        } catch (Exception ignored) {
        }
    }

    private void checkOrPending(Runtime out, String type, String id, String command, Path root) {
        TrustedExecutable spec = TrustedExecutable.of(type,
                TrustedExecutable.shortId(id + "|" + command), "plugin", command,
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(),
                root.toString(), TrustedExecutable.referencedScripts(command, root));
        if (trustStore == null || !trustStore.isTrusted(spec))
            out.pendingTrust.add(id + " → " + command);
    }

    public PluginInstaller installer() { return installer; }
}
