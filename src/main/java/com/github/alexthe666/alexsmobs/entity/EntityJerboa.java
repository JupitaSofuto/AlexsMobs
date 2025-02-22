package com.github.alexthe666.alexsmobs.entity;

import com.github.alexthe666.alexsmobs.config.AMConfig;
import com.github.alexthe666.alexsmobs.effect.AMEffectRegistry;
import com.github.alexthe666.alexsmobs.entity.ai.AnimalAIWanderRanged;
import com.github.alexthe666.alexsmobs.entity.ai.JerboaAIBeg;
import com.github.alexthe666.alexsmobs.misc.AMSoundRegistry;
import com.github.alexthe666.alexsmobs.misc.AMTagRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

public class EntityJerboa extends Animal {

    private static final EntityDataAccessor<Boolean> JUMP_ACTIVE = SynchedEntityData.defineId(EntityJerboa.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> BEGGING = SynchedEntityData.defineId(EntityJerboa.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SLEEPING = SynchedEntityData.defineId(EntityJerboa.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> BEFRIENDED = SynchedEntityData.defineId(EntityJerboa.class, EntityDataSerializers.BOOLEAN);
    public float jumpProgress;
    public float prevJumpProgress;
    public float reboundProgress;
    public float prevReboundProgress;
    public float begProgress;
    public float prevBegProgress;
    public float sleepProgress;
    public float prevSleepProgress;
    private int jumpTicks;
    private int jumpDuration;
    private boolean wasOnGround;
    private int currentMoveTypeDuration;

    protected EntityJerboa(EntityType<? extends Animal> jerboa, Level lvl) {
        super(jerboa, lvl);
        this.moveControl = new EntityJerboa.MoveHelperController(this);
        this.jumpControl = new JumpHelperController(this);
    }

    public static AttributeSupplier.Builder bakeAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 4.0D).add(Attributes.MOVEMENT_SPEED, 0.45F);
    }


    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(JUMP_ACTIVE, false);
        this.entityData.define(BEGGING, false);
        this.entityData.define(SLEEPING, false);
        this.entityData.define(BEFRIENDED, false);
    }

    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new JerboaAIBeg(this, 1.0D));
        this.goalSelector.addGoal(2, new BreedGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new AvoidEntityGoal(this, Player.class, 5, 1.3D, 1.0D) {
            public boolean canUse() {
                return !EntityJerboa.this.isBefriended() && super.canUse();
            }
        });
        this.goalSelector.addGoal(4, new AvoidEntityGoal(this, Cat.class, 9, 1.3D, 1.0D));
        this.goalSelector.addGoal(5, new AvoidEntityGoal(this, Ocelot.class, 9, 1.3D, 1.0D));
        this.goalSelector.addGoal(6, new AvoidEntityGoal(this, EntityRattlesnake.class, 9, 1.3D, 1.0D));
        this.goalSelector.addGoal(7, new PanicGoal(this, 1.1D));
        this.goalSelector.addGoal(8, new AnimalAIWanderRanged(this, 20, 1.0D, 10, 7));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
    }

    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setBefriended(compound.getBoolean("Befriended"));
        this.setSleeping(compound.getBoolean("Sleeping"));
    }

    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("Befriended", isBefriended());
        compound.putBoolean("Sleeping", isSleeping());
    }

    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return !requiresCustomPersistence();
    }

    public boolean requiresCustomPersistence() {
        return super.requiresCustomPersistence() || this.isBefriended();
    }

    protected SoundEvent getAmbientSound() {
        return AMSoundRegistry.JERBOA_IDLE.get();
    }

    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        return AMSoundRegistry.JERBOA_HURT.get();
    }

    protected SoundEvent getDeathSound() {
        return AMSoundRegistry.JERBOA_HURT.get();
    }


    public void tick() {
        super.tick();
        this.prevJumpProgress = jumpProgress;
        this.prevReboundProgress = reboundProgress;
        this.prevSleepProgress = sleepProgress;
        this.prevBegProgress = begProgress;
        if (!this.level().isClientSide) {
            this.entityData.set(JUMP_ACTIVE, !this.onGround());
        }
        if (this.entityData.get(JUMP_ACTIVE)) {
            if (jumpProgress < 5F) {
                jumpProgress += 1F;
                if (reboundProgress > 0) {
                    reboundProgress--;
                }
            }
            if (jumpProgress >= 5F) {
                if (reboundProgress < 5F) {
                    reboundProgress += 1;
                }
            }
        } else {
            if (reboundProgress > 0) {
                reboundProgress = Math.max(reboundProgress - 1F, 0);
            }
            if (jumpProgress > 0) {
                jumpProgress = Math.max(jumpProgress - 1F, 0);
            }
        }

        if (this.isBegging()) {
            if (begProgress < 5F)
                begProgress++;
        } else {
            if (begProgress > 0F)
                begProgress--;
        }

        if (this.isSleeping()) {
            if (sleepProgress < 5F)
                sleepProgress++;
        } else {
            if (sleepProgress > 0F)
                sleepProgress--;
        }

        if (!this.level().isClientSide) {
            if (this.level().isDay() && this.getLastHurtByMob() == null && !this.isBegging()) {
                if (tickCount % 10 == 0 && this.getRandom().nextInt(750) == 0) {
                    this.setSleeping(true);
                }
            } else if (this.isSleeping()) {
                this.setSleeping(false);
            }
        }
    }

    public boolean isBegging() {
        return this.entityData.get(BEGGING);
    }

    public void setBegging(boolean begging) {
        this.entityData.set(BEGGING, Boolean.valueOf(begging));
    }

    public boolean isSleeping() {
        return this.entityData.get(SLEEPING);
    }

    public void setSleeping(boolean sleeping) {
        this.entityData.set(SLEEPING, Boolean.valueOf(sleeping));
    }

    public boolean isBefriended() {
        return this.entityData.get(BEFRIENDED);
    }

    public void setBefriended(boolean befriended) {
        this.entityData.set(BEFRIENDED, Boolean.valueOf(befriended));
    }


    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        if ((itemstack.is(AMTagRegistry.JERBOA_BEGS_FOR) || isFood(itemstack)) && (this.getHealth() < this.getMaxHealth() || !this.isBefriended())) {
            if (!player.isCreative()) {
                itemstack.shrink(1);
            }
            this.setBefriended(true);
            this.heal(4);
            return InteractionResult.SUCCESS;
        }
        InteractionResult type = super.mobInteract(player, hand);
        if (type != InteractionResult.SUCCESS && !isFood(itemstack) && itemstack.is(AMTagRegistry.JERBOA_BEGS_FOR)) {
            this.setSleeping(false);
            this.gameEvent(GameEvent.ENTITY_INTERACT);
            this.playSound(SoundEvents.PARROT_EAT, this.getVoicePitch(), this.getSoundVolume());
            for (int i = 0; i < 6 + random.nextInt(3); i++) {
                double d2 = this.random.nextGaussian() * 0.02D;
                double d0 = this.random.nextGaussian() * 0.02D;
                double d1 = this.random.nextGaussian() * 0.02D;
                this.level().addParticle(new ItemParticleOption(ParticleTypes.ITEM, itemstack), this.getX() + (double) (this.random.nextFloat() * this.getBbWidth()) - (double) this.getBbWidth() * 0.5F, this.getY() + this.getBbHeight() * 0.5F + (double) (this.random.nextFloat() * this.getBbHeight() * 0.5F), this.getZ() + (double) (this.random.nextFloat() * this.getBbWidth()) - (double) this.getBbWidth() * 0.5F, d0, d1, d2);
            }
            if (random.nextFloat() <= 0.3F) {
                player.addEffect(new MobEffectInstance(AMEffectRegistry.FLEET_FOOTED.get(), 12000));
            }
            return InteractionResult.SUCCESS;
        }
        return type;
    }

    public boolean hurt(DamageSource source, float amount) {
        boolean prev = super.hurt(source, amount);
        if (prev) {
            this.setSleeping(false);
            if (source.getEntity() != null) {
                if (source.getEntity() instanceof LivingEntity) {
                    LivingEntity hurter = (LivingEntity) source.getEntity();
                    if (hurter.hasEffect(AMEffectRegistry.FLEET_FOOTED.get())) {
                        hurter.removeEffect(AMEffectRegistry.FLEET_FOOTED.get());
                    }
                }
            }
            return prev;
        }
        return prev;
    }

    public boolean isFood(ItemStack stack) {
        return stack.is(AMTagRegistry.JERBOA_BREEDABLES);
    }

    public boolean shouldMove() {
        return !isSleeping();
    }

    public float getJumpCompletion(float partialTicks) {
        return this.jumpDuration == 0 ? 0.0F : ((float) this.jumpTicks + partialTicks) / (float) this.jumpDuration;
    }

    protected float getJumpPower() {
        return horizontalCollision ? super.getJumpPower() + 0.2F : 0.25F + random.nextFloat() * 0.15F;
    }

    public boolean causeFallDamage(float distance, float damageMultiplier) {
        return false;
    }

    protected void checkFallDamage(double y, boolean onGroundIn, BlockState state, BlockPos pos) {
    }


    public static boolean isValidLightLevel(ServerLevelAccessor p_223323_0_, BlockPos p_223323_1_, RandomSource p_223323_2_) {
        int light = p_223323_0_.getMaxLocalRawBrightness(p_223323_1_);
        return light <= 4;

    }

    public boolean checkSpawnRules(LevelAccessor worldIn, MobSpawnType spawnReasonIn) {
        return AMEntityRegistry.rollSpawn(AMConfig.jerboaSpawnRolls, this.getRandom(), spawnReasonIn);
    }

    public static boolean canMonsterSpawnInLight(EntityType<? extends EntityJerboa> p_223325_0_, ServerLevelAccessor p_223325_1_, MobSpawnType p_223325_2_, BlockPos p_223325_3_, RandomSource p_223325_4_) {
        return isValidLightLevel(p_223325_1_, p_223325_3_, p_223325_4_) && checkMobSpawnRules(p_223325_0_, p_223325_1_, p_223325_2_, p_223325_3_, p_223325_4_);
    }

    public static <T extends Mob> boolean canJerboaSpawn(EntityType<EntityJerboa> entityType, ServerLevelAccessor iServerWorld, MobSpawnType reason, BlockPos pos, RandomSource random) {
        return reason == MobSpawnType.SPAWNER || iServerWorld.canSeeSky(pos.above()) && canMonsterSpawnInLight(entityType, iServerWorld, reason, pos, random);
    }

    protected void jumpFromGround() {
        super.jumpFromGround();
        double d0 = this.moveControl.getSpeedModifier();
        if (d0 > 0.0D) {
            double d1 = this.getDeltaMovement().horizontalDistance();
            if (d1 < 0.01D) {
            }
        }

        if (!this.level().isClientSide) {
            this.level().broadcastEntityEvent(this, (byte) 1);
        }

    }

    public void setMovementSpeed(double newSpeed) {
        this.getNavigation().setSpeedModifier(newSpeed);
        this.moveControl.setWantedPosition(this.moveControl.getWantedX(), this.moveControl.getWantedY(), this.moveControl.getWantedZ(), newSpeed);
    }

    public void startJumping() {
        this.setJumping(true);
        this.jumpDuration = 10;
        this.jumpTicks = 0;
    }

    private void checkLandingDelay() {
        this.updateMoveTypeDuration();
        this.disableJumpControl();
    }

    private void calculateRotationYaw(double x, double z) {
        this.setYRot((float) (Mth.atan2(z - this.getZ(), x - this.getX()) * (double) Mth.RAD_TO_DEG) - 90.0F);
    }

    private void enableJumpControl() {
        if (jumpControl instanceof EntityJerboa.JumpHelperController) {
            ((EntityJerboa.JumpHelperController) this.jumpControl).setCanJump(true);
        }
    }

    private void disableJumpControl() {
        if (jumpControl instanceof EntityJerboa.JumpHelperController) {
            ((EntityJerboa.JumpHelperController) this.jumpControl).setCanJump(false);
        }
    }

    private void updateMoveTypeDuration() {
        if (this.moveControl.getSpeedModifier() < 2.2D) {
            this.currentMoveTypeDuration = 2;
        } else {
            this.currentMoveTypeDuration = 1;
        }

    }

    public void customServerAiStep() {
        super.customServerAiStep();

        if (this.currentMoveTypeDuration > 0) {
            --this.currentMoveTypeDuration;
        }

        if (this.onGround() && this.shouldMove()) {
            if (!this.wasOnGround) {
                this.setJumping(false);
                this.checkLandingDelay();
            }

            if (this.currentMoveTypeDuration == 0) {
                LivingEntity livingentity = this.getTarget();
                if (livingentity != null && this.distanceToSqr(livingentity) < 16.0D) {
                    this.calculateRotationYaw(livingentity.getX(), livingentity.getZ());
                    this.moveControl.setWantedPosition(livingentity.getX(), livingentity.getY(), livingentity.getZ(), this.moveControl.getSpeedModifier());
                    this.startJumping();
                    this.wasOnGround = true;
                }
            }
            if (this.jumpControl instanceof EntityJerboa.JumpHelperController) {
                EntityJerboa.JumpHelperController rabbitController = (EntityJerboa.JumpHelperController) this.jumpControl;
                if (!rabbitController.getIsJumping()) {
                    if (this.moveControl.hasWanted() && this.currentMoveTypeDuration == 0) {
                        Path path = this.navigation.getPath();
                        Vec3 vector3d = new Vec3(this.moveControl.getWantedX(), this.moveControl.getWantedY(), this.moveControl.getWantedZ());
                        if (path != null && !path.isDone()) {
                            vector3d = path.getNextEntityPos(this);
                        }

                        this.calculateRotationYaw(vector3d.x, vector3d.z);
                        this.startJumping();
                    }
                } else if (!rabbitController.canJump()) {
                    this.enableJumpControl();
                }
            }
        } else if (!this.shouldMove()) {
            this.setJumping(false);
            this.checkLandingDelay();
        }

        this.wasOnGround = this.onGround();
    }

    public void aiStep() {
        super.aiStep();
        if (this.jumpTicks != this.jumpDuration) {
            ++this.jumpTicks;
        } else if (this.jumpDuration != 0) {
            this.jumpTicks = 0;
            this.jumpDuration = 0;
            this.setJumping(false);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void handleEntityEvent(byte id) {
        if (id == 1) {
            this.spawnSprintParticle();
            this.jumpDuration = 10;
            this.jumpTicks = 0;
        } else {
            super.handleEntityEvent(id);
        }

    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel p_146743_, AgeableMob p_146744_) {
        EntityJerboa boa = AMEntityRegistry.JERBOA.get().create(p_146743_);
        boa.setBefriended(true);
        return boa;
    }

    public boolean hasJumper() {
        return jumpControl instanceof JumpHelperController;
    }

    static class MoveHelperController extends MoveControl {
        private final EntityJerboa jerboa;
        private double nextJumpSpeed;

        public MoveHelperController(EntityJerboa jerboa) {
            super(jerboa);
            this.jerboa = jerboa;
        }

        public void tick() {
            if (this.jerboa.hasJumper() && this.jerboa.onGround() && !this.jerboa.jumping && !((EntityJerboa.JumpHelperController) this.jerboa.jumpControl).getIsJumping()) {
                this.jerboa.setMovementSpeed(0.0D);
            } else if (this.hasWanted()) {
                this.jerboa.setMovementSpeed(this.nextJumpSpeed);
            }
            if (this.operation == MoveControl.Operation.MOVE_TO) {
                this.operation = MoveControl.Operation.WAIT;
                Vec3 vector3d = new Vec3(this.wantedX - jerboa.getX(), this.wantedY - jerboa.getY(), this.wantedZ - jerboa.getZ());
                double d0 = vector3d.length();
                jerboa.setDeltaMovement(jerboa.getDeltaMovement().add(vector3d.scale(this.speedModifier * 1.0F * 0.05D / d0)));

            }
            super.tick();

        }

        /**
         * Sets the speed and location to move to
         */
        public void setWantedPosition(double x, double y, double z, double speedIn) {
            if (this.jerboa.isInWater()) {
                speedIn = 1.5D;
            }

            super.setWantedPosition(x, y, z, speedIn);
            if (speedIn > 0.0D) {
                this.nextJumpSpeed = speedIn;
            }

        }
    }

    public static class JumpHelperController extends JumpControl {
        private final EntityJerboa jerboa;
        private boolean canJump;

        public JumpHelperController(EntityJerboa jerboa) {
            super(jerboa);
            this.jerboa = jerboa;
        }

        public boolean getIsJumping() {
            return this.jump;
        }

        public boolean canJump() {
            return this.canJump;
        }

        public void setCanJump(boolean canJumpIn) {
            this.canJump = canJumpIn;
        }

        public void tick() {
            if (this.jump) {
                this.jerboa.startJumping();
                this.jump = false;
            }

        }
    }
}