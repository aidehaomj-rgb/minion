# MINION 原生桌面入口（A款）

目标：Win7 SP1 x64 + 既有Java8/JavaFX8，使用A款蓝色神经M。**不是把BAT改名为EXE，不引入.NET或新的用户运行库。** 编译器只在开发机使用。

## 实现

- `launcher.c`构建真正的AMD64 Windows GUI程序`Minion.exe`。隐藏控制台，定位自身所在目录的程序JAR；优先复用`jdk8/bin/javaw.exe`、`jre/bin/javaw.exe`，其次MINION_JAVA/JAVA_HOME。不调用cmd，不联网下载依赖。
- Java入口在任何GUI前设置固定`Minion.AI.Desktop`进程身份；真实JavaFX HWND设置同一ID和指向Minion.exe的重新启动命令、图标、名称资源。
- 快捷方式目标和图标指向同目录Minion.exe，写入同一AppUserModelID。不会把通用javaw.exe当作固定入口。
- 窗口图标来自`src/resource/app`的16/24/32/48/64/128/256透明PNG，原生EXE内嵌相同多尺寸ICO；主题弹窗也使用该图标。不会替换其他Material工具按钮图形。

## 用户安装方式（集成新版JAR后）

1. 保留原安装目录的模型配置、会话、知识库、项目、Java和Python。
2. 将配套的新JAR、Minion.exe及Create-Minion-Shortcuts.cmd置于原安装目录；不能只把EXE搭配旧V45 JAR就认为任务栏绑定完整。
3. 运行Create-Minion-Shortcuts.cmd，创建当前用户桌面/开始菜单的MINION快捷方式，无需管理员权限。
4. 如果有以前固定的Java/BAT入口，先手动取消固定；再右键新的MINION快捷方式选择“固定到任务栏”。本程序不会擅自改动你的任务栏布局。
5. 此后从桌面或任务栏启动；路径、EXE名称和AppID在后续升级中保持稳定。

已存在的同名快捷方式不会被覆盖；如确为旧MINION快捷方式，可手动移除该快捷方式后重建，不能误删其他应用入口。不要把安装目录放到任何一级以`!`结尾的目录，Java8资源URL有该限制。

## 开发构建

图标主稿：`docs/design/minion-app-icons/A-selected-master.png`，由内置image_gen依据用户选定A款编辑，真实透明背景。`BuildIconAssets.java`只做确定性的尺寸/ICO格式转换，不改变设计内容。构建机使用现有Java8：

```
javac -encoding UTF-8 -d target/desktop-icon-builder tools/desktop-app/BuildIconAssets.java
java -cp target/desktop-icon-builder BuildIconAssets docs/design/minion-app-icons/A-selected-master.png src/resource/app
python tools/desktop-app/build-native.py --compiler <tcc.exe> --output target/desktop-app
python tools/desktop-app/verify-native.py --compiler <tcc.exe> --exe target/desktop-app/Minion.exe
```

构建工具来自TinyCC官方引用的GNU发布目录：`https://download.savannah.gnu.org/releases/tinycc/tcc-0.9.27-win64-bin.zip`；只留在target，不分发编译器。资源写入使用Windows BeginUpdateResourceW/UpdateResourceW，不需要另外安装Windows SDK/windres。源码和构建流程一并保留，可重新链接构建。

## 测试边界

原生QA只在临时测试目录创建.lnk，不操作真实桌面/任务栏；验证中文/空格/&/内部!目录、参数引号/反斜线/中文往返、工作目录、缺失文件/Java、快捷方式目标/图标/AppID、已有快捷方式不覆盖、EXE嵌入图标与名称资源、PE无CLR和仅系统DLL导入。

DesktopShellSmoke实际创建JavaFX窗口，读取真实进程/窗口AppID与重新启动属性、检查主窗口/弹窗7种图标，不以源码字符串检查冒充Windows Shell验证。

这些是本机Windows+Java8证据；**真实Win7上的任务栏固定/重启/升级与签名信誉提示仍需试机**。目前EXE未做商业代码签名。未自动修改真实任务栏，未上传图标/新代码到GitHub，也未覆盖既有V45交付包。

技术依据：[Microsoft AppUserModelID](https://learn.microsoft.com/en-us/windows/win32/shell/appids)、[窗口重新启动属性](https://learn.microsoft.com/en-us/windows/win32/properties/props-system-appusermodel-relaunchcommand)、[TinyCC官网](https://bellard.org/tcc/)。
