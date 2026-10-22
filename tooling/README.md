# 仓库工具

- `workspace.py`：产品工作目录入口与根目录、文档分类和本地链接检查。
- `sync_update_feed.py`：从唯一现行发行记录生成旧客户端仍使用的 raw 更新地址。
- `hooks/`：两版共用的提交质量钩子。
- `website/`：网站组装工具，源码位于根目录 `website/`。
- `worktrees.sh`：开发工作树检查。
- `archive/workflows/`：停止自动运行的上游工作流，仅作维护参考。
- `editors/`：非生产的编辑器辅助资料。

产品专属工具分别保留在 `apps/aerove/tool/` 和 `apps/aerove-lite/tool/`，不互相调用运行时。
