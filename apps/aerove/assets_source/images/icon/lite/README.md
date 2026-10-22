# AEROVE Lite 品牌资源

正式显示名称：**AEROVE Lite**。独立Android原生/Rust产品，与完整版Aerove分开安装、存储和更新。

图标采用原版飘带的反色逻辑：**白底、黑飘带**，保留折叠层次，不加字母或Lite文字。源母版为`aerove-lite-icon-tile.png`，通过内置imagegen编辑原版`../aerove-icon-tile.png`生成。完整提示见`aerove-lite-icon.prompt.txt`。完整版图标未修改。

Android工程位于`android-lite/`，启动器保留独立白底黑飘带图标。启动页单独使用`aerove-lite-splash.png`透明飘带，由内置imagegen从图标母版去除底图生成，提示见`aerove-lite-splash.prompt.txt`；对应drawable与源图逐字节相同，主题负责全屏纯白及深色系统栏图标。不得运行完整版图标导出脚本覆盖现有资源。真实设备启动与安全区验收仍须记录，透明PNG和XML检查不等于设备渲染通过。
