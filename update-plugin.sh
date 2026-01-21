#!/bin/bash

# 快速更新脚本 - 简化版
# 用法: ./update-plugin.sh

set -e

echo "🚀 开始更新插件..."

# 1. 打包插件
echo "📦 正在打包..."
./gradlew buildPlugin -x buildSearchableOptions -x test

# 2. 查找生成的文件
PLUGIN_ZIP=$(ls -t build/distributions/*.zip 2>/dev/null | head -1)

if [ -z "$PLUGIN_ZIP" ]; then
    echo "❌ 打包失败，未找到 zip 文件"
    exit 1
fi

echo "✅ 打包完成: $PLUGIN_ZIP"
echo ""
echo "📋 下一步操作:"
echo ""
echo "方法 1 - 在 Android Studio 中更新:"
echo "  1. 完全关闭 Android Studio"
echo "  2. 重新打开 Android Studio"
echo "  3. Settings > Plugins > 点击齿轮图标 > Install plugin from disk..."
echo "  4. 选择: $PLUGIN_ZIP"
echo "  5. 点击 OK 并重启 Android Studio"
echo ""
echo "方法 2 - 手动替换插件文件:"
echo "  插件目录通常在:"
echo "  ~/Library/Application Support/Google/AndroidStudio*/plugins/"
echo ""
echo "📝 如果需要完全自动化，请使用: ./update-android-studio.sh"
