# 本地定制与上游同步

本仓库使用以下分支约定：

- `main`：上游源码基线，不在此分支开发定制功能。
- `codex/custom`：内网 Win7 定制、离线打包脚本和后续功能开发。
- `upstream`：原作者仓库 `https://github.com/aacw1/minion.git`。

## 日常开发

```bat
git switch codex/custom
```

每个功能独立提交，提交前使用 JDK 8 + JavaFX 运行完整测试：

```bat
mvn clean package
```

## 定期同步上游

当前基线来自上游提交 `ce92ec3943d63dab7e845f714544ce24501d5144` 的源码 ZIP。
首次成功连接 GitHub Git 服务后，应先把本地基线对齐到真正的上游历史：

```bat
git fetch upstream main
git branch local-import-main main
git switch codex/custom
git rebase --onto upstream/main main codex/custom
git branch -f main upstream/main
git branch --set-upstream-to=upstream/main main
```

完成首次对齐后，以后的常规同步流程为：

```bat
git fetch upstream
git switch main
git merge --ff-only upstream/main
git switch codex/custom
git merge main
```

若合并出现冲突，处理后必须重新运行完整测试并生成新的 Win7 离线包。

## 安全约定

- 不提交 `model.json`、API Key、Cookie、会话记录或内网地址。
- 保持 Java 8 字节码、JavaFX 8 和 Windows 7 x64 兼容性。
- 上游升级 Java 版本或依赖前，先做 Win7 兼容性评估。
