# 插件快速更新指南

本目录包含两个脚本，用于快速更新 Android Studio 中的插件。

## 📋 脚本说明

### 1. `update-plugin.sh` ⚡ (推荐日常使用)

快速打包插件，生成可安装的 zip 文件。

**特点:**
- 快速打包（跳过测试和 searchable options）
- 显示手动更新步骤
- 适合频繁修改代码时的快速迭代

**使用方法:**
```bash
./update-plugin.sh
```

**输出:**
```
✅ 打包完成: build/distributions/jetbrains-index-mcp-plugin-3.1.0.zip
```

### 2. `update-android-studio.sh` 🤖 (完全自动化)

自动检测、打包并更新 Android Studio 中的插件。

**特点:**
- 自动查找 Android Studio 配置目录
- 自动备份旧插件
- 自动替换新插件
- 彩色输出，步骤清晰

**使用方法:**
```bash
./update-android-studio.sh
```

**前提条件:**
- Android Studio 已安装并至少启动过一次
- 插件已在 Android Studio 中安装过（用于检测插件目录）

## 🚀 推荐工作流

### 快速迭代开发

```bash
# 1. 修改代码
vim src/main/kotlin/...

# 2. 打包插件
./update-plugin.sh

# 3. 在 Android Studio 中更新
# Settings > Plugins > 齿轮图标 > Install plugin from disk...
# 选择生成的 zip 文件
# 重启 Android Studio
```

### 完全自动化（首次配置后）

```bash
# 首次使用需要手动配置一次，之后可以一键更新
./update-android-studio.sh

# 脚本会自动:
# - 检测 Android Studio
# - 打包插件
# - 备份旧版本
# - 安装新版本

# 重启 Android Studio 即可
```

## 📝 Android Studio 手动更新步骤

如果不使用脚本，或者脚本无法自动检测，可以手动更新：

### 方法 1: 通过插件管理器（推荐）

1. **关闭 Android Studio**（完全退出，不是最小化）
2. **重新打开 Android Studio**
3. **打开插件设置**:
   - `macOS`: `Android Studio` > `Settings` > `Plugins`
   - `Windows/Linux`: `File` > `Settings` > `Plugins`
4. **点击齿轮图标** ⚙️（在插件列表右上角）
5. **选择** `Install plugin from disk...`
6. **选择打包文件**:
   ```
   build/distributions/jetbrains-index-mcp-plugin-3.1.0.zip
   ```
7. **点击 OK** 确认安装
8. **重启 Android Studio**

### 方法 2: 手动替换插件文件

1. **打包插件**:
   ```bash
   ./update-plugin.sh
   ```

2. **找到插件目录**（常见位置）:
   ```bash
   # macOS
   ~/Library/Application Support/Google/AndroidStudio*/plugins/

   # Linux
   ~/.config/Google/AndroidStudio*/plugins/

   # Windows
   %APPDATA%\Google\AndroidStudio*\plugins\
   ```

3. **解压并替换**:
   ```bash
   # 备份旧版本
   cp -r plugins/ide-index-mcp-server plugins/ide-index-mcp-server.backup

   # 解压新版本
   unzip -o build/distributions/jetbrains-index-mcp-plugin-3.1.0.zip -d plugins/

   # 重启 Android Studio
   ```

## 🔧 故障排除

### 问题 1: 脚本执行权限错误

```bash
chmod +x update-plugin.sh
chmod +x update-android-studio.sh
```

### 问题 2: 找不到 Android Studio 配置目录

**解决方案:**
1. 确保已至少启动过一次 Android Studio
2. 检查常见位置:
   ```bash
   ls ~/Library/Application\ Support/Google/
   ```

### 问题 3: 插件更新后不生效

**解决方案:**
1. **完全关闭 Android Studio**（确保进程完全退出）
   ```bash
   # macOS
   killall "Android Studio"

   # Windows
   taskkill /F /IM studio64.exe
   ```

2. **检查插件版本**:
   - 打开 `Settings` > `Plugins`
   - 找到 "IDE Index MCP Server"
   - 查看版本号

3. **清除缓存**（如果问题持续）:
   - `File` > `Invalidate Caches...` > `Invalidate and Restart`

### 问题 4: 打包失败

**解决方案:**
```bash
# 清理并重新构建
./gradlew clean buildPlugin -x buildSearchableOptions -x test

# 如果还是失败，查看详细错误
./gradlew buildPlugin --info
```

## 📊 速度对比

| 方法 | 时间 | 适用场景 |
|------|------|---------|
| update-plugin.sh + 手动安装 | ~5秒 | 日常开发 |
| update-android-studio.sh | ~10秒 | 批量更新 |
| 完全手动 | ~30秒 | 首次安装 |

## 💡 提示

1. **开发时**: 使用 `update-plugin.sh` 快速打包
2. **批量更新**: 使用 `update-android-studio.sh` 自动化
3. **首次安装**: 必须手动安装一次，之后才能使用自动检测
4. **版本验证**: 更新后在 `Settings` > `Plugins` 中检查版本号

## 🎯 快速命令参考

```bash
# 快速打包
./update-plugin.sh

# 完全自动化更新
./update-android-studio.sh

# 手动打包（IDEA Gradle 工具）
./gradlew buildPlugin -x buildSearchableOptions -x test

# 查看生成的文件
ls -lh build/distributions/
```

## 📞 需要帮助?

如果遇到问题，请检查:
1. Android Studio 是否完全关闭
2. 插件 zip 文件是否生成成功
3. 插件目录路径是否正确
4. 查看脚本输出的错误信息
