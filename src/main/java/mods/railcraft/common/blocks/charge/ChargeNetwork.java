/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2018
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/

package mods.railcraft.common.blocks.charge;

import com.google.common.collect.ForwardingCollection;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingSet;
import com.google.common.collect.Iterators;
import mods.railcraft.api.charge.Charge;
import mods.railcraft.api.charge.IBatteryBlock;
import mods.railcraft.api.charge.IChargeBlock;
import mods.railcraft.api.charge.IChargeProtectionItem;
import mods.railcraft.common.core.RailcraftConfig;
import mods.railcraft.common.items.ModItems;
import mods.railcraft.common.plugins.forge.WorldPlugin;
import mods.railcraft.common.util.entity.RCEntitySelectors;
import mods.railcraft.common.util.entity.RailcraftDamageSource;
import mods.railcraft.common.util.misc.Game;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by CovertJaguar on 7/23/2016 for Railcraft.
 *
 * @author CovertJaguar <http://www.railcraft.info>
 */
public class ChargeNetwork implements Charge.INetwork {
    public static final double CHARGE_PER_DAMAGE = 1000.0;
    public static final EnumMap<IChargeBlock.ConnectType, ConnectionMap> CONNECTION_MAPS = new EnumMap<>(IChargeBlock.ConnectType.class);
    private final ChargeGrid NULL_GRID = new NullGrid();
    private final Map<BlockPos, ChargeNode> nodes = new HashMap<>();
    private final Map<BlockPos, ChargeNode> queue = new LinkedHashMap<>();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final Set<ChargeNode> tickingNodes = new LinkedHashSet<>();
    private final Set<ChargeGrid> grids = Collections.newSetFromMap(new WeakHashMap<>());
    private final ChargeNode NULL_NODE = new NullNode();
    private final Charge network;
    private final WeakReference<World> world;
    private final BatterySaveData batterySaveData;

    public ChargeNetwork(Charge network, World world) {
        this.network = network;
        this.world = new WeakReference<>(world);
        this.batterySaveData = BatterySaveData.forWorld(world);
    }

    private void printDebug(String msg, Object... args) {
        if (RailcraftConfig.printChargeDebug())
            Game.log(Level.INFO, msg, args);
    }

    public void tick() {
        tickingNodes.removeIf(ChargeNode::checkUsageRecordingCompletion);

        // Process the queue of nodes waiting to be added/removed from the network
        Map<BlockPos, ChargeNode> added = new LinkedHashMap<>();
        Iterator<Map.Entry<BlockPos, ChargeNode>> iterator = queue.entrySet().iterator();
        int count = 0;
        while (iterator.hasNext() && count < 500) {
            count++;
            Map.Entry<BlockPos, ChargeNode> action = iterator.next();
            if (action.getValue() == null) {
                removeNodeImpl(action.getKey());
            } else {
                addNodeImpl(action.getKey(), action.getValue());
                added.put(action.getKey(), action.getValue());
            }
            iterator.remove();
        }

        // Search for connected nodes of recently added nodes and register them too
        // helps fill out the graph faster and more reliably
        Set<BlockPos> newNodes = new HashSet<>();
        for (Map.Entry<BlockPos, ChargeNode> addedNode : added.entrySet()) {
            forConnections(addedNode.getKey(), (conPos, conState) -> {
                if (addNode(conPos, conState))
                    newNodes.add(conPos);
            });
            if (addedNode.getValue().isGridNull())
                addedNode.getValue().constructGrid();
        }

        // Remove discarded grids and tick what's left
        grids.removeIf(g -> g.invalid);
        grids.forEach(ChargeGrid::tick);

        if (!newNodes.isEmpty())
            printDebug("Nodes queued: {0}", newNodes.size());
    }

    private void forConnections(BlockPos pos, BiConsumer<BlockPos, IBlockState> action) {
        World worldObj = world.get();
        if (worldObj == null)
            return;
        IBlockState state = WorldPlugin.getBlockState(worldObj, pos);
        if (state.getBlock() instanceof IChargeBlock) {
            IChargeBlock.ChargeSpec chargeSpec = getChargeDef(state, pos);
            if (chargeSpec != null) {
                CONNECTION_MAPS.get(chargeSpec.getConnectType()).forEach((k, v) -> {
                    BlockPos otherPos = pos.add(k);
                    IBlockState otherState = WorldPlugin.getBlockState(worldObj, otherPos);
                    if (otherState.getBlock() instanceof IChargeBlock) {
                        IChargeBlock.ChargeSpec other = getChargeDef(otherState, otherPos);
                        if (other != null && CONNECTION_MAPS.get(other.getConnectType()).get(pos.subtract(otherPos)).contains(chargeSpec.getConnectType())) {
                            action.accept(otherPos, otherState);
                        }
                    }
                });
            }
        }
    }

    /**
     * Add the node to the network and clean up any node that used to exist there
     */
    private void addNodeImpl(BlockPos pos, ChargeNode node) {
        ChargeNode oldNode = nodes.put(pos, node);

        // update the battery in the save data tracker
        if (node.chargeBattery != null)
            batterySaveData.initBattery(node.chargeBattery);
        else
            batterySaveData.removeBattery(pos);

        // clean up any preexisting node
        if (oldNode != null) {
            oldNode.invalid = true;
            if (oldNode.chargeGrid.isActive()) {
                node.chargeGrid = oldNode.chargeGrid;
                node.chargeGrid.add(node);
            }
            oldNode.chargeGrid = NULL_GRID;
        }
    }

    private void removeNodeImpl(BlockPos pos) {
        ChargeNode chargeNode = nodes.remove(pos);
        if (chargeNode != null) {
            chargeNode.invalid = true;
            chargeNode.chargeGrid.destroy(true);
        }
        batterySaveData.removeBattery(pos);
    }

    @Override
    public boolean addNode(BlockPos pos, IBlockState state) {
        IChargeBlock.ChargeSpec chargeSpec = getChargeDef(state, pos);
        if (chargeSpec != null && needsNode(pos, chargeSpec)) {
            printDebug("Registering Node: {0}->{1}", pos, chargeSpec);
            queue.put(pos, new ChargeNode(pos, chargeSpec));
            return true;
        }
        return false;
    }

    private @Nullable IChargeBlock.ChargeSpec getChargeDef(IBlockState state, BlockPos pos) {
        World worldObj = world.get();
        if (worldObj == null)
            return null;
        if (state.getBlock() instanceof IChargeBlock) {
            return ((IChargeBlock) state.getBlock()).getChargeDef(network, state, worldObj, pos);
        }
        return null;
    }

    @Override
    public void removeNode(BlockPos pos) {
        queue.put(pos, null);
    }

    public ChargeGrid grid(BlockPos pos) {
        return access(pos).getGrid();
    }

    @Override
    public ChargeNode access(BlockPos pos) {
        ChargeNode node = nodes.get(pos);
        if (node != null && !node.isValid()) {
            removeNodeImpl(pos);
            node = null;
        }
        if (node == null) {
            World worldObj = world.get();
            if (worldObj != null) {
                IBlockState state = WorldPlugin.getBlockState(worldObj, pos);
                IChargeBlock.ChargeSpec chargeSpec = getChargeDef(state, pos);
                if (chargeSpec != null) {
                    node = new ChargeNode(pos, chargeSpec);
                    addNodeImpl(pos, node);
                    if (node.isGridNull())
                        node.constructGrid();
                }
            }
        }
        return node == null ? NULL_NODE : node;
    }

    private boolean needsNode(BlockPos pos, IChargeBlock.ChargeSpec chargeSpec) {
        ChargeNode node = nodes.get(pos);
        return node == null || !node.isValid() || node.chargeSpec != chargeSpec;
    }

    public class ChargeGrid extends ForwardingSet<ChargeNode> {
        private final Set<ChargeNode> chargeNodes = new HashSet<>();
        private final List<BatteryBlock> batteries = new ArrayList<>();
        private boolean invalid;
        private double totalLosses;
        private double chargeUsedThisTick;
        private double averageUsagePerTick;

        @Override
        protected Set<ChargeNode> delegate() {
            return chargeNodes;
        }

        @Override
        public boolean add(ChargeNode chargeNode) {
            boolean added = super.add(chargeNode);
            if (added)
                totalLosses += chargeNode.chargeSpec.getLosses();
            chargeNode.chargeGrid = this;
            batteries.removeIf(b -> b.getPos().equals(chargeNode.pos));
            if (chargeNode.chargeBattery != null) {
                batteries.removeIf(b -> b.getPos().equals(chargeNode.pos));
                batteries.add(chargeNode.chargeBattery);
                batteries.sort(Comparator.comparing(BatteryBlock::getState));
            } else {
                batterySaveData.removeBattery(chargeNode.pos);
            }
            return added;
        }

        @Override
        public boolean addAll(Collection<? extends ChargeNode> collection) {
            return standardAddAll(collection);
        }

        @Override
        public boolean remove(Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super ChargeNode> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<ChargeNode> iterator() {
            return Iterators.unmodifiableIterator(super.iterator());
        }

        private void destroy(boolean touchNodes) {
            if (isActive()) {
                printDebug("Destroying grid: {0}", this);
                invalid = true;
                totalLosses = 0.0;
                if (touchNodes) {
                    forEach(n -> n.chargeGrid = NULL_GRID);
                }
                batteries.clear();
                super.clear();
                grids.remove(this);
            }
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        private void tick() {
            removeCharge(totalLosses * RailcraftConfig.chargeLossMultiplier());

            // balance the charge in all the rechargeable batteries in the grid
            Set<BatteryBlock> rechargeable = batteries.stream()
                    .unordered()
                    .filter(b -> {
                        switch (b.getState()) {
                            case DISABLED:
                            case DISPOSABLE:
                                return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toSet());

            double capacity = rechargeable.stream().mapToDouble(BatteryBlock::getCapacity).sum();
            if (capacity > 0.0) {
                final double charge = rechargeable.stream().mapToDouble(BatteryBlock::getCharge).sum();
                final double chargeLevel = charge / capacity;
                rechargeable.forEach(bat -> {
                    bat.setCharge(chargeLevel * bat.getCapacity());
                    batterySaveData.updateBatteryRecord(bat);
                });
            }

            batteries.forEach(BatteryBlock::tick);

            // track usage patterns
            averageUsagePerTick = (averageUsagePerTick * 49D + chargeUsedThisTick) / 50D;
            chargeUsedThisTick = 0.0;
        }

        public double getCharge() {
            return batteries.stream().mapToDouble(BatteryBlock::getCharge).sum();
        }

        public double getCapacity() {
            return batteries.stream().mapToDouble(BatteryBlock::getCapacity).sum();
        }

        public double getAvailableCharge() {
            return batteries.stream().mapToDouble(BatteryBlock::getAvailableCharge).sum();
        }

        public double getPotentialDraw() {
            return batteries.stream().mapToDouble(BatteryBlock::getPotentialDraw).sum();
        }

        public double getEfficiency() {
            return batteries.stream().mapToDouble(BatteryBlock::getEfficiency).average().orElse(1.0);
        }

        public int getComparatorOutput() {
            double capacity = getCapacity();
            if (capacity <= 0.0)
                return 0;
            double level = getCharge() / capacity;
            return Math.round((float) (15.0 * level));
        }

        public double getLosses() {
            return totalLosses;
        }

        public double getAverageUsagePerTick() {
            return averageUsagePerTick;
        }

        public double getUtilization() {
            if (isInfinite())
                return 0.0;
            double potentialDraw = getPotentialDraw();
            if (potentialDraw <= 0.0)
                return 1.0;
            return Math.min(getAverageUsagePerTick() / potentialDraw, 1.0);
        }

        public boolean isInfinite() {
            return batteries.stream().anyMatch(b -> b.getState() == IBatteryBlock.State.INFINITE);
        }

        public boolean isActive() {
            return !isNull();
        }

        public boolean isNull() {
            return false;
        }

        /**
         * Remove the requested amount of charge if possible and
         * return whether sufficient charge was available to perform the operation.
         *
         * @return true if charge could be removed in full
         */
        public boolean useCharge(double amount) {
            double efficiency = getEfficiency();
            if (getAvailableCharge() >= amount / efficiency) {
                removeCharge(amount, efficiency);
                return true;
            }
            return false;
        }

        /**
         * Determines if the grid is capable of providing the required charge.
         * The amount of charge that can be withdraw from the grid is dependant on the overall efficiency
         * of the grid and its available charge.
         *
         * @param amount the requested charge
         * @return true if the grid can provide the power
         */
        public boolean hasCapacity(double amount) {
            return getAvailableCharge() >= amount / getEfficiency();
        }

        /**
         * Remove up to the requested amount of charge and returns the amount
         * removed.
         *
         * @return charge removed
         */
        public double removeCharge(double desiredAmount) {
            return removeCharge(desiredAmount, getEfficiency());
        }

        /**
         * Remove up to the requested amount of charge and returns the amount
         * removed.
         *
         * @return charge removed
         */
        private double removeCharge(double desiredAmount, double efficiency) {
            final double amountToDraw = desiredAmount / efficiency;
            double amountNeeded = amountToDraw;
            for (BatteryBlock battery : batteries) {
                amountNeeded -= battery.removeCharge(amountNeeded);
                batterySaveData.updateBatteryRecord(battery);
                if (amountNeeded <= 0.0)
                    break;
            }
            double chargeRemoved = amountToDraw - amountNeeded;
            chargeUsedThisTick += chargeRemoved;
            return chargeRemoved * efficiency;
        }

        @Override
        public String toString() {
            return String.format("ChargeGrid{s=%d,b=%d}", size(), batteries.size());
        }
    }

    private class NullGrid extends ChargeGrid {
        @Override
        protected Set<ChargeNode> delegate() {
            return Collections.emptySet();
        }

        @Override
        public boolean isNull() {
            return true;
        }

        @Override
        public String toString() {
            return "ChargeGrid{NullGrid}";
        }
    }

    private class UsageRecorder {
        private final int ticksToRecord;
        private final Consumer<Double> usageConsumer;

        private double chargeUsed;
        private int ticksRecorded;

        public UsageRecorder(int ticksToRecord, Consumer<Double> usageConsumer) {
            this.ticksToRecord = ticksToRecord;
            this.usageConsumer = usageConsumer;
        }

        public void useCharge(double amount) {
            chargeUsed += amount;
        }

        public Boolean run() {
            ticksRecorded++;
            if (ticksRecorded > ticksToRecord) {
                usageConsumer.accept(chargeUsed / ticksToRecord);
                return false;
            }
            return true;
        }
    }

    public class ChargeNode implements Charge.IAccess {
        protected final @Nullable BatteryBlock chargeBattery;
        private final BlockPos pos;
        private final IChargeBlock.ChargeSpec chargeSpec;
        private ChargeGrid chargeGrid = NULL_GRID;
        private boolean invalid;
        private Optional<UsageRecorder> usageRecorder = Optional.empty();
        private final Collection<BiConsumer<ChargeNode, Double>> listeners = new LinkedHashSet<>();

        private ChargeNode(BlockPos pos, IChargeBlock.ChargeSpec chargeSpec) {
            this.pos = pos;
            this.chargeSpec = chargeSpec;
            this.chargeBattery = chargeSpec.getBatterySpec() == null ? null : new BatteryBlock(pos, chargeSpec.getBatterySpec());
        }

        public IChargeBlock.ChargeSpec getChargeSpec() {
            return chargeSpec;
        }

        private void forConnections(Consumer<ChargeNode> action) {
            CONNECTION_MAPS.get(chargeSpec.getConnectType()).forEach((k, v) -> {
                BlockPos otherPos = pos.add(k);
                ChargeNode other = nodes.get(otherPos);
                if (other != null && v.contains(other.chargeSpec.getConnectType())
                        && CONNECTION_MAPS.get(other.chargeSpec.getConnectType()).get(pos.subtract(otherPos)).contains(chargeSpec.getConnectType())) {
                    action.accept(other);
                }
            });
        }

        public ChargeGrid getGrid() {
            if (chargeGrid.isActive())
                return chargeGrid;
            constructGrid();
            return chargeGrid;
        }

        public void addListener(BiConsumer<ChargeNode, Double> listener) {
            listeners.add(listener);
        }

        public void removeListener(BiConsumer<ChargeNode, Double> listener) {
            listeners.remove(listener);
        }

        public void startUsageRecording(int ticksToRecord, Consumer<Double> usageConsumer) {
            usageRecorder = Optional.of(new UsageRecorder(ticksToRecord, usageConsumer));
            tickingNodes.add(this);
        }

        public boolean checkUsageRecordingCompletion() {
            usageRecorder = usageRecorder.filter(UsageRecorder::run);
            return !usageRecorder.isPresent();
        }

        @Override
        public boolean hasCapacity(double amount) {
            return chargeGrid.hasCapacity(amount);
        }

        @Override
        public boolean useCharge(double amount) {
            boolean removed = chargeGrid.useCharge(amount);
            if (removed) {
                listeners.forEach(c -> c.accept(this, amount));
                usageRecorder.ifPresent(r -> r.useCharge(amount));
            }
            return removed;
        }

        @Override
        public double removeCharge(double desiredAmount) {
            double removed = chargeGrid.removeCharge(desiredAmount);
            listeners.forEach(c -> c.accept(this, removed));
            usageRecorder.ifPresent(r -> r.useCharge(removed));
            return removed;
        }

        public boolean isValid() {
            return !invalid;
        }

        public boolean isGridNull() {
            return chargeGrid.isNull();
        }

        protected void constructGrid() {
            Set<ChargeNode> visitedNodes = new HashSet<>();
            visitedNodes.add(this);
            Set<ChargeNode> nullNodes = new HashSet<>();
            nullNodes.add(this);
            Deque<ChargeNode> nodeQueue = new ArrayDeque<>();
            nodeQueue.add(this);
            TreeSet<ChargeGrid> grids = new TreeSet<>(Comparator.comparingInt(ForwardingCollection::size));
            grids.add(chargeGrid);
            ChargeNode nextNode;
            while ((nextNode = nodeQueue.poll()) != null) {
                nextNode.forConnections(n -> {
                    if (!visitedNodes.contains(n) && (n.isGridNull() || !grids.contains(n.chargeGrid))) {
                        if (n.isGridNull())
                            nullNodes.add(n);
                        grids.add(n.chargeGrid);
                        visitedNodes.add(n);
                        nodeQueue.addLast(n);
                    }
                });
            }
            chargeGrid = Objects.requireNonNull(grids.pollLast());
            if (chargeGrid.isNull() && nullNodes.size() > 1) {
                chargeGrid = new ChargeGrid();
                ChargeNetwork.this.grids.add(chargeGrid);
            }
            if (chargeGrid.isActive()) {
                int originalSize = chargeGrid.size();
                chargeGrid.addAll(nullNodes);
                for (ChargeGrid grid : grids) {
                    chargeGrid.addAll(grid);
                }
                grids.forEach(g -> g.destroy(false));
                printDebug("Constructing Grid: {0}->{1} Added {2} nodes", pos, chargeGrid, chargeGrid.size() - originalSize);
            }
        }

        @Override
        public void zap(Entity entity, Charge.DamageOrigin origin, float damage) {
            if (Game.isClient(entity.world))
                return;

            if (!RCEntitySelectors.KILLABLE.test(entity))
                return;

            double chargeCost = damage * CHARGE_PER_DAMAGE;

            if (hasCapacity(chargeCost)) {
                float remainingDamage = damage;
                if (entity instanceof EntityLivingBase) {
                    EntityLivingBase livingEntity = (EntityLivingBase) entity;
                    EnumMap<EntityEquipmentSlot, IChargeProtectionItem> protections = new EnumMap<>(EntityEquipmentSlot.class);
                    EnumSet.allOf(EntityEquipmentSlot.class).forEach(slot -> {
                                IChargeProtectionItem protection = getChargeProtection(livingEntity, slot);
                                if (protection != null)
                                    protections.put(slot, protection);
                            }
                    );
                    for (Map.Entry<EntityEquipmentSlot, IChargeProtectionItem> e : protections.entrySet()) {
                        if (remainingDamage > 0.1) {
                            IChargeProtectionItem.ZepResult result = e.getValue().zap(livingEntity.getItemStackFromSlot(e.getKey()), livingEntity, remainingDamage);
                            entity.setItemStackToSlot(e.getKey(), result.stack);
                            remainingDamage -= result.damagePrevented;
                        } else break;
                    }
                }
                if (remainingDamage > 0.1 && entity.attackEntityFrom(origin == Charge.DamageOrigin.BLOCK ? RailcraftDamageSource.ELECTRIC : RailcraftDamageSource.TRACK_ELECTRIC, remainingDamage)) {
                    removeCharge(chargeCost);
                    Charge.effects().zapEffectDeath(entity.world, entity);
                }
            }
        }

        private @Nullable IChargeProtectionItem getChargeProtection(EntityLivingBase entity, EntityEquipmentSlot slot) {
            ItemStack stack = entity.getItemStackFromSlot(slot);
            Item item = stack.getItem();
            if (item instanceof IChargeProtectionItem && ((IChargeProtectionItem) item).isZapProtectionActive(stack, entity)) {
                return (IChargeProtectionItem) item;
            }
            if (ModItems.RUBBER_BOOTS.isEqual(stack, false, false)
                    || ModItems.STATIC_BOOTS.isEqual(stack, false, false)) {
                return new IChargeProtectionItem() {
                };
            }
            return null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ChargeNode that = (ChargeNode) o;

            return pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return pos.hashCode();
        }

        @Override
        public @Nullable BatteryBlock getBattery() {
            return chargeBattery;
        }

        @Override
        public int getComparatorOutput() {
            return getGrid().getComparatorOutput();
        }

        @Override
        public String toString() {
            String string = String.format("ChargeNode{%s}|%s", pos, chargeSpec.toString());
            if (chargeBattery != null)
                string += "|State: " + chargeBattery.getState();
            return string;
        }
    }

    public class NullNode extends ChargeNode {
        public NullNode() {
            super(new BlockPos(0, 0, 0), new IChargeBlock.ChargeSpec(IChargeBlock.ConnectType.BLOCK, 0.0));
        }

        @Override
        public @Nullable BatteryBlock getBattery() {
            return null;
        }

        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public ChargeGrid getGrid() {
            return NULL_GRID;
        }

        @Override
        public boolean hasCapacity(double amount) {
            return false;
        }

        @Override
        public boolean useCharge(double amount) {
            return false;
        }

        @Override
        public double removeCharge(double desiredAmount) {
            return 0.0;
        }

        @Override
        protected void constructGrid() {
        }

        @Override
        public String toString() {
            return "ChargeNode{NullNode}";
        }
    }

    static {
        EnumSet<IChargeBlock.ConnectType> any = EnumSet.allOf(IChargeBlock.ConnectType.class);
        EnumSet<IChargeBlock.ConnectType> notWire = EnumSet.complementOf(EnumSet.of(IChargeBlock.ConnectType.WIRE));
        EnumSet<IChargeBlock.ConnectType> track = EnumSet.of(IChargeBlock.ConnectType.TRACK);
        EnumSet<IChargeBlock.ConnectType> notFlat = EnumSet.complementOf(EnumSet.of(IChargeBlock.ConnectType.TRACK, IChargeBlock.ConnectType.SLAB));
        ConnectionMap positions;

        // BLOCK

        positions = new ConnectionMap();
        for (EnumFacing facing : EnumFacing.VALUES) {
            positions.put(facing.getDirectionVec(), any);
        }
        CONNECTION_MAPS.put(IChargeBlock.ConnectType.BLOCK, positions);

        // SLAB
        positions = new ConnectionMap();

        positions.put(new BlockPos(+1, 0, 0), notWire);
        positions.put(new BlockPos(-1, 0, 0), notWire);

        positions.put(new BlockPos(0, -1, 0), any);

        positions.put(new BlockPos(0, 0, +1), notWire);
        positions.put(new BlockPos(0, 0, -1), notWire);

        CONNECTION_MAPS.put(IChargeBlock.ConnectType.SLAB, positions);

        //TRACK
        positions = new ConnectionMap();

        positions.put(new BlockPos(+1, 0, 0), notWire);
        positions.put(new BlockPos(-1, 0, 0), notWire);

        positions.put(new BlockPos(+1, +1, 0), track);
        positions.put(new BlockPos(+1, -1, 0), track);

        positions.put(new BlockPos(-1, +1, 0), track);
        positions.put(new BlockPos(-1, -1, 0), track);

        positions.put(new BlockPos(0, -1, 0), any);

        positions.put(new BlockPos(0, 0, +1), notWire);
        positions.put(new BlockPos(0, 0, -1), notWire);

        positions.put(new BlockPos(0, +1, +1), track);
        positions.put(new BlockPos(0, -1, +1), track);

        positions.put(new BlockPos(0, +1, -1), track);
        positions.put(new BlockPos(0, -1, -1), track);

        CONNECTION_MAPS.put(IChargeBlock.ConnectType.TRACK, positions);

        // WIRE
        positions = new ConnectionMap();

        positions.put(new BlockPos(+1, 0, 0), notFlat);
        positions.put(new BlockPos(-1, 0, 0), notFlat);
        positions.put(new BlockPos(0, +1, 0), any);
        positions.put(new BlockPos(0, -1, 0), notFlat);
        positions.put(new BlockPos(0, 0, +1), notFlat);
        positions.put(new BlockPos(0, 0, -1), notFlat);

        CONNECTION_MAPS.put(IChargeBlock.ConnectType.WIRE, positions);
    }

    private static class ConnectionMap extends ForwardingMap<Vec3i, EnumSet<IChargeBlock.ConnectType>> {

        private final Map<Vec3i, EnumSet<IChargeBlock.ConnectType>> delegate;

        public ConnectionMap() {
            delegate = new HashMap<>();
        }

        @Override
        protected Map<Vec3i, EnumSet<IChargeBlock.ConnectType>> delegate() {
            return delegate;
        }

        @Override
        public EnumSet<IChargeBlock.ConnectType> get(@Nullable Object key) {
            EnumSet<IChargeBlock.ConnectType> ret = super.get(key);
            return ret == null ? EnumSet.noneOf(IChargeBlock.ConnectType.class) : ret;
        }
    }
}
