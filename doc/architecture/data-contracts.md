# Ghidra I18N 数据契约
#
# 本文档定义各阶段之间的数据交换格式，是各 Pipeline 阶段的 API 契约。
# 所有阶段必须严格遵守此契约，确保数据在 extract → translate → transform 之间无歧义传递。

## 阶段间数据流

```
                     ┌──────────────┐
                     │  ghidra/     │  上游 submodule（只读源码）
                     └──────┬───────┘
                            │
                     ┌──────▼───────┐
    Phase 2:          │   extract    │
    String Extraction └──────┬───────┘
                            │ extraction-manifest.json
                            │ (List<TranslationUnit>)
                     ┌──────▼───────┐
    Phase 3:          │  translate   │
    AI Translation    └──────┬───────┘
                            │ translated units (updated manifest)
                            │ messages_*.properties (per module)
                     ┌──────▼───────┐
    Phase 4:          │  transform   │
    Source Rewrite    └──────┬───────┘
                            │ modified Java source files
                            │ injected I18n*.java bootstrap
                            │ injected Messages*.properties
                     ┌──────▼───────┐
    Phase 5:          │   build      │
    Build & Release   └──────────────┘
```

## 核心数据模型

### TranslationUnit (翻译单元)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | ✅ | 全局唯一 key，格式 `{ModuleName}.{ClassName}.{field}.{context}` |
| `moduleName` | String | ✅ | Gradle 子模块名 (Docking, Base, Debugger 等) |
| `sourceFilePath` | String | ✅ | 相对于 submodule 根目录的文件路径 |
| `className` | String | ✅ | 简单类名 |
| `fullClassName` | String | ✅ | 完整限定类名（含包名） |
| `pattern` | ExtractionPattern | ✅ | 提取模式枚举 |
| `sourceText` | String | ✅ | 原始英文文本 |
| `priority` | Priority | ✅ | P0(必须)/P1(重要)/P2(辅助) |
| `context` | UiContext | | UI 上下文标签，用于翻译消歧义 |
| `isHtml` | boolean | | 是否含 HTML 标签 |
| `hasFormatSpecifier` | boolean | | 是否含 %s %d {0} 格式化占位符 |
| `containsMnemonic` | boolean | | 是否含 & 助记符标记 |
| `aiReviewStatus` | AiReviewStatus | | AI 审核状态 |
| `manualApproval` | boolean | | 是否需人工审批 |
| `translation_zh_CN` | String | | 简中翻译文本 |
| `translationStatus` | TranslationStatus | | 翻译状态 |
| `lastModified` | Instant | | ISO 8601 最后修改时间 |

### ExtractionPattern 枚举值

```
SET_TITLE             - setTitle("...")
SET_TOOL_TIP_TEXT     - setToolTipText("...")
NEW_JBUTTON           - new JButton("...")
NEW_JLABEL            - new JLabel("...")
NEW_GLABEL            - new GLabel("...")
NEW_GHTML_LABEL       - new GHtmlLabel("...")
NEW_JMENU_ITEM        - new JMenuItem("...")
NEW_JMENU             - new JMenu("...")
NEW_JCHECK_BOX        - new JCheckBox("...")
NEW_JRADIO_BUTTON     - new JRadioButton("...")
NEW_TITLED_BORDER     - new TitledBorder("...")
NEW_JFRAME            - new JFrame("...")
NEW_JDIALOG           - new JDialog("...")
PLUGIN_SHORT_DESC     - @PluginInfo(shortDescription="...")
PLUGIN_DESCRIPTION    - @PluginInfo(description="...")
SHOW_DIALOG           - OptionDialog.show*("...")
SHOW_CONFIRM_DIALOG   - showConfirmDialog("...")
SHOW_ERROR_DIALOG     - showErrorDialog("...")
SHOW_MESSAGE_DIALOG   - showMessageDialog("...")
DOCKING_ACTION_NAME   - new DockingAction("name", ...)
DOCKING_ACTION_DESCRIPTION - DockingAction.setDescription("...")
TOC_TEXT              - TOC_Source.xml text="..."
HTML_TEXT             - .html/.htm 帮助文件中的可见文本
PY_STRING             - Python 脚本中的 UI 字符串
OTHER                 - 其他未分类
```

### AiReviewStatus

```
PENDING       - 未审核
APPROVED      - AI 确认为 UI 字符串
REJECTED      - AI 确认为非 UI 字符串（日志、异常、常量等）
NEEDS_REVIEW  - AI 不确定，需人工审核
```

### TranslationStatus

```
UNTRANSLATED        - 未翻译
MACHINE_TRANSLATED  - AI 已翻译
VERIFIED            - 人工审核确认
NEEDS_UPDATE        - 源文本已变更，需重新翻译
```

### ModuleTranslationIndex

| 字段 | 类型 | 说明 |
|------|------|------|
| `ghidraVersion` | String | Ghidra 版本号 |
| `generatedAt` | Instant | 生成时间 |
| `totalUnits` | int | 翻译单元总数 |
| `translatedUnits` | int | 已翻译单元数 |
| `coveragePercent` | double | 翻译覆盖率 |
| `modules` | Map<String, ModuleStats> | 按模块统计 |

## 输出路径约定

| 阶段 | 输出位置 |
|------|----------|
| extract | `i18n-scripts/extract/output/extraction-manifest.json` |
| translate | `i18n-scripts/translate/output/` (properties 文件) |
| transform | 直接写入 `ghidra/**/src/main/` (源码修改) |
| validate | `i18n-scripts/validate/output/coverage-report.json` |

## Key 生成规则

Java 字符串:
  `{ModuleName}.{ClassName}.{fieldName}.{context}`
  例: `Docking.LaunchErrorDialog.title` → `Docking.LaunchErrorDialog.title`

HTML 文本:
  `{ModuleName}.{htmlFileName}.{headingTag}.{counter}`
  例: `Base.Intro.H1.1` → `Introduction`

Python 字符串:
  `{ModuleName}.{scriptName}.{variable}.{counter}`
  例: `PyGhidra.launcher.string.0`

## 翻译 API 调用约定

- 批量大小: 50 条/批次
- 超时: 20 秒/批次 + 1 秒/条
- 默认 Provider: DeepSeek（OpenAI 兼容端点）
- 回退 Provider: OpenAI
- 重试: 最多 3 次，指数退避
- 翻译记忆库: 命中即跳过，不调用 API
