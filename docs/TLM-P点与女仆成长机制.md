# 车万女仆：P 点与女仆成长机制完全解析

> 本文档基于 **TLM 1.5.3** 反编译源码（Forge 1.20.1 / NeoForge 1.21.1 两版一致）逐行核对，
> 供「女仆军团」强化面板设计使用。
>
> 核对方式：`vineflower` 反编译 + 逐方法阅读，所有数值均来自源码常量，非推测。

---

## 一、P 点（Power Point）机制

### 1.1 本质：**它是「玩家」的属性，不是女仆的**

```java
// CapabilityEvent.onAttachCapabilityEvent
if (entity instanceof Player) {
    // POWER_CAP        -> PowerCapability      ← P 点挂在这里
    // MAID_NUM_CAP     -> MaidNumCapability
    // CHAT_TOKENS_CAP  -> ChatTokensCapability
}
if (entity instanceof Mob && IMaid.convert(mob) != null) {
    // GeckoMaidEntityCapabilityProvider  ← 女仆只挂这个
}
```

**结论**：P 点是**账号级（存档级、跟随玩家）的货币**，女仆身上**没有**这个字段。
所以「把 P 点给女仆」在实现上只能是「**扣除玩家的 P 点，给女仆加一个属性**」。

### 1.2 数值与上限

| 项目 | 值 | 源码位置 |
|---|---|---|
| 上限 | **5.0**（`MAX_POWER`） | `PowerCapability.MAX_POWER` |
| 初始值 | `0.0` | `PowerCapability.power` |
| 存储精度 | `float` | `serializeNBT() -> FloatTag` |
| 存储方式 | Capability，随玩家 NBT 存档 | `ICapabilitySerializable<FloatTag>` |

**API**（`PowerCapability`）：

```java
float get()          // 读余额
void add(float)      // 加（超 5.0 自动截断到 5.0）
void min(float)      // 减（不足则归 0，不会变负）
void set(float)      // 直接设置（自动 clamp 到 [0, 5]）
boolean isDirty()    // 脏标记
```

**取用方式**：

```java
player.getCapability(PowerCapabilityProvider.POWER_CAP, null)
      .ifPresent(power -> {
          float cur = power.get();
          if (cur >= cost) power.min(cost);
      });
```

### 1.3 获取途径

| 途径 | 数值 | 说明 |
|---|---|---|
| **地上捡 P 点实体** | `value / 100.0` 点 P 点 | 每个 P 点实体有 `int value`，满值 **485** = **4.85 P 点** |
| **女仆捡 P 点** | `value / 4` **经验** | 不是 P 点！女仆捡到的是**经验**（见第二章） |
| **女仆信标吸收** | `value / 100.0` 存入信标 | 信标自己有 `storagePower`，上限 100.0 |

#### P 点实体的 value 档位（`getPowerValue`）

P 点实体在生成时会把输入的 `powerValue` **向下取档**：

| 输入范围 | 实际 value | 折算 P 点 |
|---|---|---|
| ≥ 485 | 485 | 4.85 |
| ≥ 385 | 385 | 3.85 |
| ≥ 285 | 285 | 2.85 |
| ≥ 185 | 185 | 1.85 |
| ≥ 89 | 89 | 0.89 |
| ≥ 36 | **34** | 0.34 |
| ≥ 17 | **13** | 0.13 |
| ≥ 7 | 7 | 0.07 |
| ≥ 5 | 5 | 0.05 |
| ≥ 3 | 3 | 0.03 |
| 其它 | 1 | 0.01 |

> ⚠️ 注意 36 和 17 两档有**取整陷阱**（返回 34 和 13，比边界小），这是 TLM 自己的设计。

#### 捡取时的溢出转换

```java
if (power.get() + value / 100.0F > 5.0F) {
    power.add(5.0F - power.get());                       // 先补满到 5.0
    int residual = value - 500 + (int)(power.get()*100); // 溢出部分
    player.giveExperiencePoints(residual / 4);           // 转成原版经验
}
```

**即：P 点满了以后再捡，溢出部分按 `每 4 点经验值 = 1 点经验` 转成原版经验，不会浪费。**

捡取前置条件：
- `throwTime == 0`（被扔出的 P 点要落地后才能捡）
- `player.takeXpDelay == 0`（原版捡取冷却，捡完置 2 tick）
- P 点实体有寿命：**6000 tick（5 分钟）** 后消失

### 1.4 消耗途径

#### ① 女仆信标（Shrine Lamp / Maid Beacon）

```java
// TileEntityMaidBeacon.tick
if (potionIndex != -1 && storagePower >= getEffectCost()) {
    storagePower -= getEffectCost();
    // 给 8 格内所有女仆挂 100 tick 的 II 级效果
}
```

| 项目 | 默认值 | 配置项 |
|---|---|---|
| 效果花费 | **0.9 P 点/小时** | `ShrineLampEffectCost` |
| 信标最大存储 | **100.0 P 点** | `ShrineLampMaxStorage` |
| 吸收范围 | **6 格** | `ShrineLampMaxRange` |
| 效果范围 | **8 格**，100 tick，等级 II | 硬编码 |

可选效果（`BeaconEffect` 枚举，共 5 种）：
`SPEED`（速度）、`FIRE_RESISTANCE`（抗火）、`STRENGTH`（力量）、
`RESISTANCE`（抗性提升）、`REGENERATION`（生命恢复）

> **重点**：信标是**把 P 点存起来**（上限 100），然后**持续消耗**给周围女仆上 buff。
> 这是目前 P 点的**唯一常规消耗途径**——对你的强化面板来说，这是现成的「P 点仓库」参考实现。

#### ② 玩家死亡扣除

| 项目 | 默认值 | 配置项 |
|---|---|---|
| 死亡损失 | **1.0 P 点** | `PlayerDeathLossPowerPoint` |

#### ③ 祭坛合成（power cost）

祭坛配方有 `powerCost` 字段，**复活女仆是 0.5**（见第三章）。

### 1.5 显示

- `ShowPowerEvent`：客户端 HUD 事件，手里拿着御币时显示 P 点
- `SyncCapabilityMessage`：服务端 → 客户端同步 P 点数值
- `PowerCommand`：`/tlm power get|set|add` 调试命令（**可参考其实现**）

---

## 二、女仆经验（MaidExperience）机制

### 2.1 存储

```java
public static final String EXPERIENCE_TAG = "MaidExperience";
private static final EntityDataAccessor<Integer> DATA_EXPERIENCE;  // 同步字段
```

| 项目 | 说明 |
|---|---|
| 类型 | `int` |
| 存档 | NBT `MaidExperience`（随女仆实体保存，跟人走） |
| 同步 | `SynchedEntityData`（客户端可见，可直接画在面板上） |
| API | `getExperience()` / `setExperience(int)` |

> **这是好消息**：经验是**女仆自己的**、且有**同步字段**，
> 所以你的面板可以**直接在客户端显示每只女仆的经验**，不需要额外发包。

### 2.2 获取途径

#### ① 女仆捡经验球

```java
public void pickupXPOrb(ExperienceOrb orb) {
    // 1. 先尝试修复带「经验修补」的装备
    // 2. 剩余部分 setExperience(getExperience() + orb.value)
}
```

#### ② 女仆捡 P 点实体

```java
int xpValue = EntityPowerPoint.transPowerValueToXpValue(powerPoint.getValue()); // value / 4
setExperience(getExperience() + xpValue);
```

**即：女仆捡 P 点 → 得到 `value/4` 经验**（注意：**不是** P 点，别和玩家搞混）。

> ⚠️ 这是**关键设计点**：同一个 P 点实体，
> **玩家捡 → 玩家 P 点**，**女仆捡 → 女仆经验**。
> 两者走的是不同的 `onPickup` 路径，互不冲突。

### 2.3 经验的实际用途

**⚠️ 重要发现：在当前 TLM 1.5.3 里，女仆经验几乎「只进不出」——它没有内置的消耗途径。**

我用两种方式核对了这一点：
1. 搜索全代码库 `getExperience()` 的调用点 → 只有 `IMaid` 接口、GUI 显示、背包数据、经验瓶事件等**读取/显示**用途
2. 搜索 `setExperience()` → 只有捡球/捡 P 点的**写入**

调用点清单：
| 类 | 用途 |
|---|---|
| `IMaid` | 接口默认方法 |
| `AbstractMaidContainerGui` | **把经验画在女仆 GUI 上（纯显示）** |
| `BackpackRightClickMaidEvent` | 背包右击读取 |
| `FurnaceBackpackData` | 熔炉背包 |
| `GetExpBottleEvent` | 经验瓶事件（与经验修补联动） |
| `QueryBinding` | 客户端显示 |
| `EntityMaid` | 自身读写 |

**结论：女仆经验目前只是一个「展示型数值」，TLM 没有用它做任何成长判定。**
→ 这对你**极其有利**：你可以**放心地把经验定义成强化货币**，不会和 TLM 现有机制冲突。

### 2.4 对比：真正驱动成长的是「好感度」，不是经验

TLM 内置的成长系统用的是 **Favorability（好感度）**：

```java
// FavorabilityManager
private static final int LEVEL_0_POINT = 0;
private static final int LEVEL_1_POINT = 64;
private static final int LEVEL_2_POINT = 192;
private static final int LEVEL_3_POINT = 384;
```

| 好感度 | 等级 | 生命上限 | 攻击力 | 攻击距离加成 | 横扫范围 |
|---|---|---|---|---|---|
| 0 ~ 63 | 0 | 20 | 2 | 0 | 1.0 |
| 64 ~ 191 | 1 | **30** | **3** | **+1** | 2.0 |
| 192 ~ 383 | 2 | **40** | **4** | **+3** | 3.0 |
| ≥ 384 | 3 | **80** | **6** | **+5** | 4.0 |

**升级时自动应用**（`add()` 里检测 `levelBefore < levelAfter`，直接改 `Attributes.ATTACK_DAMAGE` / `MAX_HEALTH`）。

#### 好感度怎么涨（`Type` 全表）

| 行为 | 点数 | 冷却 (tick) |
|---|---|---|
| 书架 / 电脑 / 键盘 / 五子棋 / 睡觉 | +2 | 24000（20 分钟） |
| 五子棋获胜 | **+8** | 12000 |
| 中国象棋 / 国际象棋获胜 | +4 | 18000 |
| 工作时吃饭 | +1 | 3600 |
| 家模式吃饭 | +1 | 24000 |
| 普通吃饭 | +1 | 1200（1 分钟） |
| 偷吃可食用方块 | +1 | 3600 |
| **死亡** | **-2** | 12000 |

> ⚠️ **对你的死亡复活功能有直接影响**：女仆死亡会 **-2 好感度**。
> 如果你做「面板复活」，要不要保留这个惩罚、还是豁免，需要你决定。

---

## 三、死亡与墓碑机制（用于第 2、3 项需求）

### 3.1 死亡流程（`dropCustomDeathLoot` / `m_5907_`）

```java
protected void m_5907_() {   // 即 dropCustomDeathLoot
    if (getOwner() != null && !level.isClientSide && !PetBedDrop.hasPetBedPos(this)) {
        // 1. 创建墓碑实体
        EntityTombstone tombstone = new EntityTombstone(level, getOwnerUUID(), position);

        // 2. 合并所有物品栏
        CombinedInvWrapper inv = new CombinedInvWrapper(
            armorInvWrapper, handsInvWrapper, maidInv, maidBauble, hideInv, taskInv);

        // 3. 先销毁「消失诅咒」物品
        destroyVanishingCursedItems(inv);

        // 4. 把物品全部搬进墓碑
        for (int i = 0; i < inv.getSlots(); i++) {
            tombstone.insertItem(inv.extractItem(i, inv.getSlotLimit(i), false));
        }

        // 5. 背包 + 胶片（胶片 = 女仆完整数据的载体）
        tombstone.insertItem(backpack.getTakeOffItemStack(...));
        tombstone.insertItem(ItemFilm.maidToFilm(this));

        // 6. ★ 可取消事件 ★
        MaidTombstoneEvent event = new MaidTombstoneEvent(this, tombstone);
        if (MinecraftForge.EVENT_BUS.post(event)) {   // ← 被取消则直接 return
            return;
        }

        // 7. 登记到世界数据 + 生成实体
        maidWorldData.addTombstones(this, tombstone);
        this.alreadyDropped = true;
        level.addFreshEntity(tombstone);
    }
}
```

**进入条件**：`onRemovedFromWorld(KILLED)` 时且 `!alreadyDropped`。

### 3.2 ✅ `MaidTombstoneEvent` 是 `@Cancelable` 的

```java
@Cancelable
public class MaidTombstoneEvent extends Event {
    EntityMaid getMaid();
    EntityTombstone getTombstone();
}
```

**取消它 = 不生成墓碑，物品也不会被搬走**（因为 `return` 发生在实体生成之前，
但注意：**物品在取消点之前已经被 `extractItem` 搬进墓碑对象了**）。

> ⚠️ **这是一个必须注意的坑**：
> `extractItem(..., false)` 是**真的从女仆身上拿走**了，
> 取消事件只是**不生成墓碑实体**——物品会**凭空消失**！
>
> **正确做法**：在事件里取消的同时，**自己把墓碑里的物品再搬回女仆身上**，
> 或者**改用手动保存**（`saveWithoutId`）而不是依赖墓碑搬运。
> 我的实现会采用后者（见第四章）。

### 3.3 墓碑内容物

`EntityTombstone` 有一个 `ItemStackHandler items`，包含：
- 女仆的 **6 个物品栏**全部内容（护甲、主副手、女仆背包、饰品、隐藏栏、任务栏）
- 女仆**背包物品**（`getTakeOffItemStack`）
- **胶片 `ItemFilm`**（`maidToFilm` → 完整女仆 NBT，含 `MaidInfo` 标签）

打开墓碑会**把东西爆一地**——这正是你想避免的。

### 3.4 复活：祭坛配方 `reborn_maid.json`

```json
{
  "type": "touhou_little_maid:altar_crafting",
  "output": {
    "type": "touhou_little_maid:maid",
    "copy": { "ingredient": { "item": "touhou_little_maid:film" }, "tag": "MaidInfo" }
  },
  "power": 0.5,
  "ingredients": [
    { "item": "touhou_little_maid:film" },          ← 胶片（女仆数据）
    { "tag": "forge:gems/lapis" },                  ← 青金石
    { "tag": "forge:ingots/gold" },                 ← 金锭
    { "tag": "forge:dusts/redstone" },              ← 红石粉
    { "tag": "forge:ingots/iron" },                 ← 铁锭
    { "item": "minecraft:coal" }                    ← 煤炭
  ]
}
```

**这就是复活女仆的确切材料成本**（你的第 2 项需求要用它）：

| 材料 | 数量 |
|---|---|
| 胶片 Film | 1 |
| 青金石（任意 `forge:gems/lapis`） | 1 |
| 金锭（任意 `forge:ingots/gold`） | 1 |
| 红石粉（任意 `forge:dusts/redstone`） | 1 |
| 铁锭（任意 `forge:ingots/iron`） | 1 |
| 煤炭 | 1 |
| **祭坛 power** | **0.5** |

### 3.5 神龛（Shrine）真相 —— **它不是复活材料**

你的第 2 项需求提到「提交 3 个神龛」。但源码显示神龛是**单格胶片存储方块**：

```java
// TileEntityShrine
private final ItemStackHandler handler = new ItemStackHandler() {
    public boolean isItemValid(int slot, ItemStack stack) {
        return stack.getItem() == InitItems.FILM.get();   // 只接受胶片
    }
    public int getSlotLimit(int slot) { return 1; }        // 上限 1 个！
};
```

**神龛 = 「只存 1 张胶片」的展示/保管方块**（用于安全保存女仆数据，防丢失）。

> ⚠️ 所以「3 个神龛」在 TLM 里**不是一个既有概念**。
> 如果你想要「交 3 个神龛免材料复活」，那是**你自己定义的新规则**——
> 技术上完全可行（检查玩家物品栏/周围方块是否有 3 个神龛），
> 但要清楚这是**你的设计**，不是 TLM 的机制。

---

## 四、对「女仆军团」强化面板的设计建议

### 4.1 货币选择

| 方案 | 优点 | 缺点 |
|---|---|---|
| **女仆经验**（推荐） | 女仆自己的、有同步字段、TLM **完全没在用**、不冲突 | 需要自己定义「经验能买什么」 |
| 玩家 P 点 | 玩家已有概念、获取途径现成 | 上限仅 5.0，**极易耗尽**；且是全局货币 |
| 两者结合 | 经验升级，P 点加速/洗点 | 复杂度上升 |

**建议：以「女仆经验」为主货币。** 理由：
1. 经验是**女仆自己的**，符合「养成单个女仆」的直觉
2. 有 `DATA_EXPERIENCE` 同步字段，**客户端能直接读到**，UI 实现简单
3. TLM **没有**用它做任何事，**零冲突风险**
4. P 点上限只有 5.0，做不了大额消耗

### 4.2 可强化的属性（技术上都可行）

| 属性 | 实现方式 | 备注 |
|---|---|---|
| 攻击力 | `Attributes.ATTACK_DAMAGE` | 与好感度等级会**叠加**，注意平衡 |
| 生命上限 | `Attributes.MAX_HEALTH` | 同上 |
| 移动速度 | `Attributes.MOVEMENT_SPEED` | |
| 护甲 | `Attributes.ARMOR` | |
| 攻击距离 | `FavorabilityManager.getAttackDistancePlusByPoint` 相关逻辑 | 需自行接管 |
| 拾取范围 | `InitAttribute.MAID_PICKUP_RANGE` | TLM 自定义属性 |
| 弩/枪攻速 | `MAID_CROSSBOW_ATTACK_SPEED` / `MAID_GUN_ATTACK_SPEED` | TLM 自定义 |
| 饥饿上限 | `MAID_HUNGER` | TLM 自定义 |

> ⚠️ **冲突提醒**：好感度系统在**升级时**会**直接覆盖** `ATTACK_DAMAGE` 和 `MAX_HEALTH`
> （`attribute.setBaseValue(...)`，不是 `addModifier`）。
> 所以你的强化**必须用 `AttributeModifier`**（加法修饰符），
> **不能**改 baseValue —— 否则女仆一升级好感度，你的强化就被覆盖了。

### 4.3 建议的强化等级与成本（草案）

以「女仆经验」计价，参考 TLM 好感度的量级（0/64/192/384）：

| 等级 | 累计经验 | 单级效果（示例） |
|---|---|---|
| 0 | 0 | 基准 |
| 1 | 100 | +2 攻击 / +4 生命 |
| 2 | 300 | +4 攻击 / +8 生命 |
| 3 | 700 | +6 攻击 / +12 生命 |
| 4 | 1500 | +8 攻击 / +16 生命 |
| 5 | 3000 | +10 攻击 / +20 生命 |

（具体数值你来定，这里只给曲线形状参考。）

---

## 五、给实现的 API 速查

```java
// ---- 玩家 P 点 ----
player.getCapability(PowerCapabilityProvider.POWER_CAP, null).ifPresent(power -> {
    float cur = power.get();        // 当前值 [0, 5]
    power.min(0.5F);                // 扣 0.5
    power.add(0.3F);                // 加 0.3
});
// ⚠️ 1.21 已改为 NeoForge 的 capability 注册方式，且 POWER_CAP 的获取方式可能变化

// ---- 女仆经验 ----
int exp = maid.getExperience();
maid.setExperience(exp - cost);

// ---- 女仆好感度 / 等级 ----
maid.getFavorability();                       // int
maid.getFavorabilityManager().getLevel();     // 0~3
maid.getFavorabilityManager().nextLevelPoint();

// ---- 属性强化（用 Modifier，别用 baseValue！）----
AttributeInstance attack = maid.getAttribute(Attributes.ATTACK_DAMAGE);
attack.addTransientModifier(new AttributeModifier(
    UUID, "maid_legion_upgrade", amount, AttributeModifier.Operation.ADDITION));

// ---- 死亡拦截 ----
@SubscribeEvent
public void onTombstone(MaidTombstoneEvent event) {
    EntityMaid maid = event.getMaid();
    // 把墓碑里的物品搬回女仆，或改用 saveWithoutId 自行保存
    // 然后 event.setCanceled(true);
}

// ---- 女仆交互（第 1 项需求）----
@SubscribeEvent
public void onInteract(InteractMaidEvent event) {
    // event.getPlayer() / getMaid() / getStack() / getWorld()
    // 返回前 setCanceled(true) 可拦截默认行为
}

// ---- 胶片 = 女仆完整数据 ----
ItemStack film = ItemFilm.maidToFilm(maid);     // 女仆 -> 胶片
ItemFilm.filmToMaid(film, level, pos, player);  // 胶片 -> 女仆
```

---

## 六、结论摘要

| 问题 | 结论 |
|---|---|
| P 点是谁的？ | **玩家的**，上限 **5.0**，用 `PowerCapability` |
| P 点怎么来？ | 捡 P 点实体（`value/100`）、信标吸收（`value/100`） |
| P 点怎么花？ | 信标 buff（0.9/小时）、玩家死亡（-1.0）、祭坛 power |
| 女仆经验是谁的？ | **女仆自己的**，`DATA_EXPERIENCE` 同步字段 |
| 女仆经验怎么来？ | 捡经验球、捡 P 点（`value/4`） |
| 女仆经验有什么用？ | **TLM 里没用途！** ← 适合你拿来当强化货币 |
| 女仆真正成长靠什么？ | **好感度**（0/64/192/384 四级，自动改攻击力和生命） |
| 死亡会怎样？ | 生成墓碑，物品 + 胶片进墓碑，**好感度 -2** |
| 能拦截墓碑吗？ | **能**，`MaidTombstoneEvent` 是 `@Cancelable` |
| 复活要什么材料？ | 胶片 + 青金石 + 金锭 + 红石 + 铁锭 + 煤炭，祭坛 power 0.5 |
| 神龛是复活材料吗？ | **不是**，神龛是「只存 1 张胶片」的存储方块 |

---

## 七、1.2.0 期间的补充调研

### 7.1 ⚠️ 死亡好感度惩罚的事件顺序（一个真实的坑）

**结论：我们的死亡快照比 TLM 扣好感度更早，所以复活会把扣掉的分还回去。**

事件链（已用 `javap -v` 读注解确认优先级）：

```
Forge LivingDeathEvent
 ├─ MaidDeathHandler.onLivingDeath              @EventPriority.HIGHEST  ← 快照在这
 └─ TLM event/EntityDeathEvent.onEntityDeath    @SubscribeEvent（无 priority → NORMAL）
      └─ post MaidDeathEvent
           └─ event/maid/MaidDeathFavorability.onDeath
                └─ FavorabilityManager.apply(Type.DEATH)   → 好感度 −2
```

- TLM 的监听器**没有写 priority**，Forge 默认 `NORMAL`，排在 `HIGHEST` 之后。
- 所以快照里存的是**扣分前**的好感度；`EntityMaid.load(data)` 会把它读回来。
- **1.2.0 的修法**：复活后调用 `MaidProgressionService.applyDeathPenalty()`，
  按 `Type.DEATH.getPoint()`（**读出来的，不写死 2**）补扣回去；
  买了「阵亡好感度全免」能力则跳过。
- 用 `FavorabilityManager.reduce(int)` 而不是 `setFavorability(int)`，
  这样 TLM 自己的等级变更逻辑（改攻击/生命的 base 值）会正常跑。

### 7.2 伤害联动的调研结论（枪械 / 魔法）

| 目标 | 结论 |
|---|---|
| **TaCZ 枪械** | `com.tacz.guns.entity.EntityKineticBullet` **`extends Projectile`** → 走**原版 `Projectile.getOwner()`** 即可拿到射手。**不需要任何 TaCZ 依赖**，也不需要反射。 |
| TaCZ 自带的钩子 | `com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre`（TLM 自己就用它）。但我们用不上——见上一条。 |
| **万法皆通** | 它本身是**多魔法 mod 桥**（软依赖 Iron's / Ars Nouveau / Goety / Mana&Artifice / Psi / EbWizardry…），并有自己的伤害管线 `utils/MaidDamageProcessor`。 |
| **已知盲区** | 万法皆通内含 **`utils/TrueDamageUtil`（真伤）**。真伤通常**绕过 `LivingHurtEvent`**，所以**部分法术可能吃不到伤害加成**。 |
| **属性注册** | `MaidSpellEntityAttributes.onAttributeCreate(EntityAttributeCreationEvent)` 会给女仆注册它自己的属性——如果以后想做魔法专属强化，那些属性是**已经挂在女仆身上**的。 |
| 我们的实现 | `LivingHurtEvent` + 攻击者解析（`getEntity()` 是女仆，或 `getDirectEntity()` 是 `Projectile` 且 `getOwner()` 是女仆）。一条规则覆盖近战 / 箭 / 弹幕 / 枪械 / 大部分法术。 |

### 7.3 女仆身上实际可用的属性（用于强化映射）

`EntityMaid` 用的是 **`LivingEntity.createLivingAttributes()`**（不是 `createMobAttributes()`），
再自己加几个。所以完整集合是：

| 来源 | 属性 |
|---|---|
| `LivingEntity` | `MAX_HEALTH`、`MOVEMENT_SPEED`、**`ARMOR`**、`ARMOR_TOUGHNESS`、`KNOCKBACK_RESISTANCE` |
| TLM 自己加 | `ATTACK_DAMAGE`、`ATTACK_SPEED`、`ATTACK_KNOCKBACK`、`FOLLOW_RANGE`、`LUCK` |
| TLM 自定义（8 个） | `MAID_PICKUP_RANGE`、`MAID_USE_ITEM_SPEED`、`MAID_CROSSBOW_ATTACK_SPEED`、`MAID_GUN_ATTACK_SPEED`、`MAID_SHOOT_COOLDOWN`、`MAID_TRIDENT_COOLDOWN`、`MAID_PASSIVE_USE_SHIELD_TICK`、`MAID_HUNGER` |

> ❗ **护甲和移速是注册过的**，可以直接加强化。
> ❗ 好感度升级用 `setBaseValue`，所以强化**必须用 `AttributeModifier`**（加法/乘基），
> 否则会被好感度覆盖。
> ❗ 修饰符必须用 `addTransientModifier`：等级的真源在我们的 SavedData 里，
> permanent 会写进女仆自己的 NBT，下次应用时会**叠加两次**。

