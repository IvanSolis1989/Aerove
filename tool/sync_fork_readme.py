"""Keep fork distribution links separate from upstream attribution."""

import argparse
from pathlib import Path
import re
import sys


UPSTREAM = 'https://github.com/chen08209/FlClash'


def repository_name(value):
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}', value):
        raise argparse.ArgumentTypeError('expected a GitHub owner/repository')
    if value.split('/')[1] in {'.', '..'}:
        raise argparse.ArgumentTypeError('invalid repository name')
    if value.lower() == 'chen08209/flclash':
        raise argparse.ArgumentTypeError('the distribution repository must be a fork')
    return value


def replace_section(text, heading, body):
    pattern = rf'(?ms)^(## {re.escape(heading)}\n)\n.*?(?=^## |\Z)'
    text, count = re.subn(pattern, lambda match: match[1] + '\n' + body + '\n\n', text)
    if count != 1:
        raise ValueError(f'expected exactly one {heading!r} section, found {count}')
    return text


def synchronize(text, repository, chinese=False):
    base = f'https://github.com/{repository}'
    name = repository.split('/')[1]
    latest = base + '/releases/latest'
    apk = latest + '/download/XClash-android-arm64.apk'
    text = text.replace('\r\n', '\n')
    text = re.sub(r'(?m)^# (?:FlClash|XClash)$', '# ' + name, text, count=1)
    text = text.replace('src="assets/images/icon.png" alt="FlClash"',
                        f'src="assets/images/icon.png" alt="{name}"')
    if f'[FlClash]({UPSTREAM})' not in text:
        if chinese:
            intro = (f'[{name}]({base}) 是基于 chen08209 的 [FlClash]({UPSTREAM}) 开发的 '
                     '[GPL-3.0](LICENSE) 分支。\n现阶段优先开发 Android 版，视觉方向为淡雅极光与玻璃质感，'
                     '保留上游作者署名和许可声明。\n本阶段保留现有 Android 包 ID 以维持兼容性。')
        else:
            intro = (f'[{name}]({base}) is a [GPL-3.0](LICENSE) fork of '
                     f'[FlClash]({UPSTREAM}) by chen08209.\nDevelopment focuses on Android, '
                     'with a soft aurora and glass visual direction.\nUpstream authorship and license '
                     'notices are preserved; the existing Android package ID is retained for compatibility.')
        text = text.replace('# ' + name + '\n', '# ' + name + '\n\n' + intro + '\n', 1)
    text = re.sub(r'\[XClash\]\(https://github.com/[^)]+\)', f'[{name}]({base})', text)
    text = re.sub(r'(https://img.shields.io/github/(?:v/release|downloads|license)/)[^/?#]+/[^/?#]+',
                  lambda match: match[1] + repository, text)
    text = re.sub(re.escape(UPSTREAM) + r'(?=/(?:releases|issues)(?:[/?#)"\s]|$))', base, text)
    text = re.sub(r'(https://(?:api\.)?star-history.com/(?:#|svg\?repos=))[^&"\s]+',
                  lambda match: match[1] + repository, text)
    text = re.sub(r'(?m)^git clone --recursive https://github.com/[^\s]+\ncd [^\n]+',
                  f'git clone --recursive {base}.git\ncd {name}', text)
    text = re.sub(r'\[(?:Website|官网)\]\(https://chen08209.github.io/FlClash(?:/zh)?\)',
                  f'[{"仓库" if chinese else "Repository"}]({base})', text)
    text = text.replace('[Android builds and APK]', '[Android build records]')
    text = text.replace('[Android 构建与 APK]', '[Android 构建记录]')
    text = text.replace('[![Telegram]', '[![Upstream Telegram]')
    text = text.replace('https://img.shields.io/badge/Telegram-channel-',
                        'https://img.shields.io/badge/Upstream_Telegram-channel-')
    text = text.replace('The upstream documentation is retained below; its download, website and community links refer to FlClash.',
                        'Upstream screenshots and architecture documentation are retained below; downloads refer to this fork.')
    text = text.replace('以下保留上游文档，其中下载、官网及社区链接均指向 FlClash。',
                        '以下保留上游截图和架构文档；下载入口均指向本分支。')
    button = (f'<p>\n  <a href="{latest}"><img alt="Get it on GitHub" '
              'src="snapshots/get-it-on-github.svg" height="56"></a>\n</p>')
    if chinese:
        download = (f'从 [XClash Releases]({latest}) 获取本分支发布版本，或直接下载 '
                    f'[Android ARM64 APK]({apk})。\n\n'
                    '目前仅发布 Android `arm64-v8a` APK；其他架构和桌面平台暂未发布。\n'
                    '当前为固定测试签名的 Android 预览版，尚未完成真机验收。\n'
                    'Actions 页面仅用于查看构建记录。\n\n' + button)
        support = (f'欢迎给本分支点 Star；Bug 与功能建议请提交到 [XClash Issues]({base}/issues)。\n\n'
                   '上游社区：[FlClash Telegram 频道](https://t.me/FlClash)。')
    else:
        download = (f'Get this fork from [XClash Releases]({latest}), or download the '
                    f'[Android ARM64 APK]({apk}) directly.\n\n'
                    'Only Android `arm64-v8a` APKs are published at present; other architectures and desktop '
                    'platforms are not released.\n'
                    'These Android previews use a persistent test signing key; physical-device acceptance is pending.\n'
                    'Actions pages provide build records only.\n\n' + button)
        support = (f'Star this fork to support it; report bugs and feature requests in '
                   f'[XClash Issues]({base}/issues).\n\n'
                   'Upstream community: [FlClash Telegram channel](https://t.me/FlClash).')
    text = replace_section(text, '下载' if chinese else 'Download', download)
    heading = '支持' if chinese else 'Support'
    pattern = rf'(?ms)^(## {heading}\n)\n.*?(?=^<a href="https://star-history.com/)'
    text, count = re.subn(pattern, lambda match: match[1] + '\n' + support + '\n\n', text)
    if count != 1:
        raise ValueError(f'expected one {heading!r} introduction, found {count}')
    text = text.replace('- **One app for Android, Windows, macOS and Linux**, with x64 and ARM64 builds on the desktop.',
                        '- **Android first** in this fork; upstream also supports Windows, macOS and Linux.')
    text = text.replace('- **一个应用覆盖 Android、Windows、macOS 和 Linux**，桌面端同时提供 x64 与 ARM64 版本。',
                        '- **本分支优先支持 Android**；上游还支持 Windows、macOS 和 Linux。')
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True, type=repository_name)
    parser.add_argument('--check', action='store_true', help='fail if README corrections are needed')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    updates = []
    try:
        for filename in ('README.md', 'README_zh_CN.md'):
            path = args.root / filename
            before = path.read_text(encoding='utf-8')
            after = synchronize(before, args.repository, filename.endswith('_zh_CN.md'))
            if before != after:
                updates.append((path, after))
    except (OSError, ValueError) as error:
        parser.exit(2, f'{error}\n')
    for path, after in updates:
        if not args.check:
            with path.open('w', encoding='utf-8', newline='\n') as output:
                output.write(after)
        print(f'{"Needs synchronization" if args.check else "Updated"}: {path.name}')
    return 1 if args.check and updates else 0


if __name__ == '__main__':
    sys.exit(main())
