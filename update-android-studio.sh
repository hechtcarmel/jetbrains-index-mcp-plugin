#!/bin/bash

# Android Studio 插件一键更新脚本
# 用法: ./update-android-studio.sh

set -e

echo "🚀 Android Studio 插件一键更新工具"
echo "======================================"
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 插件名称
PLUGIN_NAME="ide-index-mcp-server"
PLUGIN_ZIP_NAME="jetbrains-index-mcp-plugin"

# 查找 Android Studio 配置目录
find_android_studio_config() {
    echo "🔍 正在查找 Android Studio 配置目录..."

    # 可能的配置目录位置
    possible_locations=(
        "$HOME/Library/Application Support/Google"
        "$HOME/.config/Google"
        "$HOME/.AndroidStudio"
    )

    for base_dir in "${possible_locations[@]}"; do
        if [ -d "$base_dir" ]; then
            # 查找 AndroidStudio* 目录
            as_dir=$(find "$base_dir" -maxdepth 1 -type d -name "AndroidStudio*" 2>/dev/null | head -1)
            if [ -n "$as_dir" ]; then
                echo "$as_dir"
                return 0
            fi
        fi
    done

    return 1
}

# 查找插件目录
find_plugin_dir() {
    local as_config_dir="$1"
    local plugin_dir=""

    # 方法1: 查找已安装的插件
    if [ -d "$as_config_dir/plugins" ]; then
        plugin_dir=$(find "$as_config_dir/plugins" -maxdepth 1 -type d -name "*$PLUGIN_NAME*" 2>/dev/null | head -1)
    fi

    # 方法2: 查找非内置的插件目录
    if [ -z "$plugin_dir" ] && [ -d "$as_config_dir/plugins" ]; then
        plugin_dir=$(find "$as_config_dir/plugins" -maxdepth 1 -type d -name "ide-index*" 2>/dev/null | head -1)
    fi

    echo "$plugin_dir"
}

# 检测 Android Studio 版本
get_android_studio_version() {
    local as_app="/Applications/Android Studio.app"
    if [ -d "$as_app" ]; then
        local plist="$as_app/Contents/Info.plist"
        if [ -f "$plist" ]; then
            /usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$plist" 2>/dev/null || echo "未知版本"
        fi
    fi
}

# 主流程
main() {
    # 1. 检查 Android Studio 是否安装
    echo -e "${BLUE}1️⃣  检查 Android Studio...${NC}"
    AS_VERSION=$(get_android_studio_version)
    if [ -z "$AS_VERSION" ]; then
        echo -e "${RED}❌ 未找到 Android Studio 安装${NC}"
        echo "请确认 Android Studio 已安装在 /Applications/ 目录"
        exit 1
    fi
    echo -e "${GREEN}✅ 找到 Android Studio${NC} (版本: $AS_VERSION)"
    echo ""

    # 2. 查找配置目录
    echo -e "${BLUE}2️⃣  查找配置目录...${NC}"
    AS_CONFIG_DIR=$(find_android_studio_config)
    if [ -z "$AS_CONFIG_DIR" ]; then
        echo -e "${RED}❌ 未找到 Android Studio 配置目录${NC}"
        echo "Android Studio 可能还没有启动过，请先启动一次"
        exit 1
    fi
    echo -e "${GREEN}✅ 配置目录: $AS_CONFIG_DIR${NC}"
    echo ""

    # 3. 查找插件目录
    echo -e "${BLUE}3️⃣  查找插件目录...${NC}"
    PLUGIN_DIR=$(find_plugin_dir "$AS_CONFIG_DIR")
    if [ -z "$PLUGIN_DIR" ]; then
        echo -e "${YELLOW}⚠️  未找到已安装的插件${NC}"
        echo "插件可能还没有安装，请先手动安装一次"
        read -p "是否继续打包？(y/n) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    else
        echo -e "${GREEN}✅ 插件目录: $PLUGIN_DIR${NC}"
        echo ""
    fi

    # 4. 打包插件
    echo -e "${BLUE}4️⃣  打包插件...${NC}"
    ./gradlew buildPlugin -x buildSearchableOptions -x test > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ 打包成功${NC}"
    else
        echo -e "${RED}❌ 打包失败${NC}"
        exit 1
    fi
    echo ""

    # 5. 查找生成的 zip 文件
    PLUGIN_ZIP=$(find build/distributions -name "${PLUGIN_ZIP_NAME}-*.zip" | head -1)
    if [ -z "$PLUGIN_ZIP" ]; then
        echo -e "${RED}❌ 未找到打包文件${NC}"
        exit 1
    fi
    echo -e "${GREEN}📦 打包文件: $PLUGIN_ZIP${NC}"
    echo ""

    # 6. 更新插件
    if [ -n "$PLUGIN_DIR" ]; then
        echo -e "${BLUE}5️⃣  更新插件...${NC}"

        # 备份旧版本
        if [ -d "$PLUGIN_DIR" ]; then
            BACKUP_DIR="${PLUGIN_DIR}_backup_$(date +%Y%m%d_%H%M%S)"
            echo "备份旧插件到: $BACKUP_DIR"
            cp -R "$PLUGIN_DIR" "$BACKUP_DIR"
            rm -rf "$PLUGIN_DIR"
        fi

        # 解压新插件
        echo "解压新插件到: $PLUGIN_DIR"
        mkdir -p "$PLUGIN_DIR"
        unzip -q "$PLUGIN_ZIP" -d "$AS_CONFIG_DIR/plugins/"

        # 处理解压后的目录结构
        EXTRACTED_DIR="$AS_CONFIG_DIR/plugins/${PLUGIN_ZIP_NAME}"
        if [ -d "$EXTRACTED_DIR" ] && [ ! -d "$PLUGIN_DIR" ]; then
            mv "$EXTRACTED_DIR" "$PLUGIN_DIR"
        elif [ -d "$EXTRACTED_DIR" ]; then
            # 如果插件目录已存在，复制内容
            cp -R "$EXTRACTED_DIR/"* "$PLUGIN_DIR/"
            rm -rf "$EXTRACTED_DIR"
        fi

        echo -e "${GREEN}✅ 插件更新完成${NC}"
        echo ""
    else
        echo -e "${YELLOW}⚠️  跳过插件更新（插件目录未找到）${NC}"
        echo "请手动安装打包文件: $PLUGIN_ZIP"
        echo ""
    fi

    # 7. 完成
    echo "======================================"
    echo -e "${GREEN}🎉 完成！${NC}"
    echo ""
    echo "📝 下一步操作:"
    echo "   1. 完全关闭 Android Studio（确保完全退出）"
    echo "   2. 重新启动 Android Studio"
    echo "   3. 验证插件版本是否更新"
    echo ""

    if [ -n "$PLUGIN_DIR" ]; then
        echo "🔧 如果遇到问题，可以从备份恢复:"
        echo "   rm -rf '$PLUGIN_DIR'"
        echo "   mv '$BACKUP_DIR' '$PLUGIN_DIR'"
        echo ""
    fi
}

# 运行主流程
main
