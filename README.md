# cfnew-worker — 家宽节点自动更新

CFnew v3.1 家宽链式，离线生成版。GitHub Actions 每 1 小时自动重新拉取 VPN Gate 家宽清单，重新生成 Clash 配置并提交回 main 分支。

在 Clash Meta 里订阅下面这个地址（中国移动用这个）：
- https://raw.githubusercontent.com/zhaor5869-svg/cfnew-worker/main/jk_home_cmcc.yaml

通用版：
- https://raw.githubusercontent.com/zhaor5869-svg/cfnew-worker/main/jk_home.yaml

内核要求 mihomo ≥ 1.19.25。家宽节点为志愿者共享，掉线后自动组会往下换。
