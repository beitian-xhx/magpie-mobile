# 受控 Provider 管理协议 · schema 1

基线保持54dc257f100e2ca82a843b1eea6a24b8006781f5 / Go1.26.8。
管理Cookie与原webGuard/webOwnPage/CORS保持，不是网关推理key。既有GET /api/providers保持原前端列表契约，不把其中models对象/chosen或被删减的对象用于编辑。

## 最小路径和固定字段

- POST /api/mobile/providers/create：只接受 {new:true,id,name,chat,key,models:[raw model ids],noModels:Boolean}。
- GET /api/mobile/providers/editable/{id}：只查最新配置，不触发模型刷新。
- POST /api/mobile/providers/edit：只接受 {id,revision} 与dirty name/chat/key/models/noModels。
- create/edit成功与editable返回同一裸顶层快照：{id,revision,name,chat,models:[String],noModels:Boolean,key:{set:Boolean,masked:String,optional:false}}。没有完整API key、headers、proxy或其他私有高级设置。
- models永远是数组，不是null；保留canonical中真实raw选择。有选择时不是空数组。旧空models/noModels:false代表implicit-all，非模型编辑保持原样；主动empty models才是explicit noModels:true。
- key为空白时保持原值；不存在的字段保持原值。models被提交则derive noModels，显式noModels必须与模型空/非空一致；不允许只提交noModels。
- revision是每进程随机key的HMAC-SHA256，64个小写hex，绑定路径与完整原始文件。它是不透明版本令牌，不是公开API key的裸hash；任何同文件修改或进程重启使旧令牌冲突。

## 校验、状态与未知结果

创建id必须[a-z0-9][a-z0-9-]{0,63}，不能magpie/group/auto或订阅保留id；重复id返回409，不自动加后缀。编辑id可使用当前canonical的._既有id，不能改id。
name trim后1..120 Unicode codepoints且无控制字符。key trim后1..8192 ASCII !..~，创建必须有key（本机mock也可提供synthetic值）；不支持多个key粘贴。
models至多1000个，每个trim后1..256 codepoints，无控制字符，重复值拒绝。空models创建必须noModels:true，非空必须false。
URL至多4096 codepoints；仅外部HTTPS或HTTP精确127.0.0.1/::1且显式1..65535端口。拒绝userinfo、全部query、fragment、RawPath编码、opaque URL、控制字符、空/坏端口。HTTPS literal IP拒非GlobalUnicast、private、loopback、link-local；DNS域名仅ASCII字母数字.-，有点，各label1..63且不以-开头/结尾，总长至多253，不末尾点，不是localhost、*.localhost或*.local。不进行DNS解析、test、detect或网络请求；URL级筛选不冒充DNS重绑定/实际出站防护。

请求只接受精确字段名；未知字段、重复字段、null、无效UTF-8、超过512KiB、尾随第二份JSON均400，不接受前端完整Provider对象。适用范围仅基础API-key custom OpenAI Chat：无preset、插件/订阅、Vertex、其他协议endpoint或InsecureTLS；不支持者422，不盲存为简化对象。
错误为 {error:{code,message}}，不包含请求body、完整key或配置原文：
- 400 provider_invalid_request。
- 404 provider_not_found。
- 409 provider_already_exists 或 provider_revision_conflict（provider changed; reload editable snapshot）。
- 422 provider_unsupported。
- 503 provider_locked：未写；provider_write_unknown：写入结果不明。
- 500 provider_unreadable：现有配置异常。
HTTP写入只做一次。未知结果不能自动重放；仅在用户下一次明确操作前先读安全快照/列表。未增加generic provider/save/test/detect、插件安装、删除或其他危险API。

## canonical 保留与并发控制

实际实现见[Provider事务覆盖](<overlay/internal/provider/mobile_store.go>)与[JSON读代际覆盖](<overlay/internal/provider/mobile_json.go>)。原固定Save没有完整事务锁，原store是全量Marshal，不能直接拿来承诺unknown/注释保留。
mobile事务使用进程mutex和核心filelock.Lock的providers.json.lock；held=false即拒绝，不使用filelock默认宽松fallback。锁内fresh读原始JSONC，拒绝重复/case-folded重复键、已知root/Provider tag错拼写、重复provider id、超过64层/32MiB的配置；异常文件从不自动normalize或从旧bak取编辑基线。
只对dirty primitive/原始models字段调用核心edit.PatchJSON，再拼回原Provider子对象；其余已知/未知字段、嵌套成员、评论、BOM和其他提供商不改。使用原writePrivate与lastgood.Keep私有原子保存，成功后清filememo并catalog.Touched，不执行test/detect/FetchNew。

mobile旧store也通过同一锁及read-generation CAS；旧后台snapshot不能覆盖新编辑。对于未改的known复合字段保留原字节；被改/被删的object或含复合值的array无法保证unknown nested merge时直接fail closed，不声称可完整编辑高级设置。旧generic删除/reorder也拒绝；原始标量与标量数组更新、append仍支持。非mobile的原read/store行为保持。
共享文件锁只能协调采用该锁的核心进程，外部编辑器不遵守锁的并发写入不在原子事务保证内。直接修改文件的其他维护/备份入口未开放为手机API。

## capabilities 与验证

M5原schemaVersion:1及7个基础字段保持；追加providerSchemaVersion:1与providerManagement:true，不改变基础鉴权、安全flag。
新增Go Provider/HTTP回归覆盖私有metadata创建、实际模型选择保留、空模型显式none、header/proxy/limit/key/unknown nested/comment保持、revision/旧store冲突、严格锁、unknown write不重放、重复canonical拒绝、无/错误Cookie、坏URL/未知字段/危险routes和CORS。运行这些回归需Actions包括./internal/provider、./internal/gui；本地仅Python静态/应用器校验，不能据此声称新Go编译/回归通过。
