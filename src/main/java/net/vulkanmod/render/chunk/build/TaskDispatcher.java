package net.vulkanmod.render.chunk.build;

import com.google.common.collect.Queues;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.build.task.CompileResult;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.optimization.AdaptiveChunkUploadBudget;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.visuals.render.LightUpdates;

import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.locks.LockSupport;

public class TaskDispatcher {
    private static final int MAX_PENDING_COMPILE_RESULTS = 128;
    private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();
    public final ThreadBuilderPack fixedBuffers;

    private volatile boolean stopThreads;
    private Thread[] threads;
    private BuilderResources[] resources;
    private int idleThreads;
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();
    //2026-10-31 (doors late in Deceased): sections the player changed (ChunkTask.urgent), built first and uploaded outside the budget
    private final Queue<ChunkTask> urgentTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<CompileResult> urgentResults = Queues.newConcurrentLinkedQueue();

    public TaskDispatcher() {
        this.fixedBuffers = new ThreadBuilderPack();

        this.stopThreads = true;
    }

    public void createThreads() {
        int n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
        createThreads(n);
    }

    public void createThreads(int n) {
        if(!this.stopThreads) {
            this.stopThreads();
        }

        this.stopThreads = false;

        if(this.resources != null) {
            closeResources(this.resources);
        }

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            Thread thread = new Thread(() -> runTaskThread(builderResources),
                    "Builder-" + i);
            thread.setPriority(Thread.MIN_PRIORITY);

            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(BuilderResources builderResources) {
        while(!this.stopThreads) {
            ChunkTask task = this.pollTask();

            if(task == null)
                synchronized (this) {
                    //stopThreads' notifyAll may have come between the empty poll and here: re-checked under the monitor, or this
                    //thread would wait forever and stopThreads' join with it (a level change hung the render thread, 2026-10-24)
                    if (this.stopThreads)
                        break;

                    try {
                        this.idleThreads++;
                        this.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    this.idleThreads--;
                }

            if(task == null)
                continue;

            task.markStarted();
            task.runTask(builderResources);
        }
    }

    public void schedule(ChunkTask chunkTask) {
        if(chunkTask == null)
            return;

        if (chunkTask.urgent) {
            this.urgentTasks.offer(chunkTask);
        } else if (chunkTask.highPriority) {
            this.highPriorityTasks.offer(chunkTask);
        } else {
            this.lowPriorityTasks.offer(chunkTask);
        }

        synchronized (this) {
            this.notify();
        }
    }

    @Nullable
    private ChunkTask pollTask() {
        ChunkTask task = this.urgentTasks.poll();

        if(task == null)
            task = this.highPriorityTasks.poll();

        if(task == null)
            task = this.lowPriorityTasks.poll();

        return task;
    }

    public void stopThreads() {
        if(this.stopThreads)
            return;

        this.stopThreads = true;

        synchronized (this) {
            this.notifyAll();
        }

        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        this.clearBatchQueue();
        closeResources(this.resources);
        this.fixedBuffers.closeAll();
        this.resources = null;
        this.threads = null;

    }

    private static void closeResources(BuilderResources[] resourcesArray) {
        if (resourcesArray == null) {
            return;
        }

        for (BuilderResources resources : resourcesArray) {
            if (resources != null) {
                resources.close();
            }
        }
    }

    /** Applies compiled meshes within this frame's budget; true when one of them changed the section graph (walk it again). */
    public boolean updateSections() {
        CompileResult result;
        boolean graphChanged = false;
        int uploadsThisFrame = 0;
        int maxUploadsPerFrame = this.getMaxUploadsPerFrame();
        int pendingUploads = this.compileResults.size();
        long uploadStartNanos = System.nanoTime();
        long uploadTimeBudgetNanos = Initializer.CONFIG.adaptiveChunkUploads
                ? AdaptiveChunkUploadBudget.uploadTimeBudgetNanos(pendingUploads)
                : Long.MAX_VALUE;

        while((result = this.nextResult(uploadsThisFrame < maxUploadsPerFrame)) != null) {
            graphChanged |= doSectionUpdate(result);
            if (result.urgent) continue;
            uploadsThisFrame++;

            if (uploadsThisFrame > 0 && System.nanoTime() - uploadStartNanos >= uploadTimeBudgetNanos) {
                break;
            }
        }
        if (net.vulkanmod.visuals.dev.FrameRecorder.ON) net.vulkanmod.visuals.dev.FrameRecorder.add(net.vulkanmod.visuals.dev.FrameRecorder.SECTIONS, uploadsThisFrame);

        return graphChanged;
    }

    private int getMaxUploadsPerFrame() {
        int configuredUploads = Initializer.CONFIG.chunkUploadsPerFrame;
        return Initializer.CONFIG.adaptiveChunkUploads
                ? AdaptiveChunkUploadBudget.chooseBudget(configuredUploads, this.compileResults.size())
                : clampMaxUploadsPerFrame(configuredUploads);
    }

    public static int clampMaxUploadsPerFrame(int uploadsPerFrame) {
        return Math.max(1, Math.min(16, uploadsPerFrame));
    }

    /** The next mesh to upload: an urgent one whatever the budget, else one in the order built while budgetLeft. */
    @Nullable
    private CompileResult nextResult(boolean budgetLeft) {
        CompileResult result = this.urgentResults.poll();
        return result != null || !budgetLeft ? result : this.compileResults.poll();
    }

    public void scheduleSectionUpdate(CompileResult compileResult) {
        if (compileResult.urgent && !this.stopThreads) {
            this.urgentResults.add(compileResult);
            return;
        }

        while (!this.stopThreads && this.compileResults.size() >= MAX_PENDING_COMPILE_RESULTS) {
            LockSupport.parkNanos(1_000_000L);
        }

        if (this.stopThreads) {
            compileResult.releaseBuffers();
            return;
        }

        this.compileResults.add(compileResult);
    }

    private boolean doSectionUpdate(CompileResult compileResult) {
        if (!compileResult.matchesCurrentSection()) {
            compileResult.releaseBuffers();
            return false;
        }

        RenderSection section = compileResult.renderSection;
        ChunkArea renderArea = section.getChunkArea();
        DrawBuffers drawBuffers = renderArea.getDrawBuffers();

        if(compileResult.fullUpdate) {
            var renderLayers = compileResult.renderedLayers;
            for(TerrainRenderType renderType : TerrainRenderType.VALUES) {
                UploadBuffer uploadBuffer = renderLayers.get(renderType);

                if(uploadBuffer != null) {
                    drawBuffers.upload(section, uploadBuffer, renderType);
                } else {
                    section.getDrawParameters(renderType).reset(renderArea, renderType);
                }
            }

            //DH gate A3: the graph walk stops at an uncompiled section, so a mesh that opens a way through must be walked again
            boolean graphChanged = compileResult.changesGraph();
            compileResult.updateSection();
            section.meshUploaded(compileResult.taskCreatedNanos);
            //The visuals: a light change waiting for this section's new mesh is taken now (LightUpdates.holdForMeshes)
            LightUpdates.meshUploaded(section.xOffset() >> 4, section.yOffset() >> 4, section.zOffset() >> 4);
            return graphChanged;
        }
        else {
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
            return false;
        }
    }

    public int pendingUploads() {
        return this.compileResults.size() + this.urgentResults.size();
    }

    public boolean isIdle() { return this.idleThreads == this.threads.length && this.compileResults.isEmpty() && this.urgentResults.isEmpty(); }

    public void clearBatchQueue() {
        ChunkTask urgentTask;
        while ((urgentTask = this.urgentTasks.poll()) != null) {
            urgentTask.discard();
        }

        while(!this.highPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.highPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.discard();
            }
        }

        while(!this.lowPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.lowPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.discard();
            }
        }

        CompileResult compileResult;
        while ((compileResult = this.compileResults.poll()) != null) {
            compileResult.releaseBuffers();
        }

        while ((compileResult = this.urgentResults.poll()) != null) {
            compileResult.releaseBuffers();
        }
    }

    public String getStats() {
        int taskCount = urgentTasks.size() + highPriorityTasks.size() + lowPriorityTasks.size();
        return String.format("iT: %d Ts: %d uQ: %d", this.idleThreads, taskCount, this.pendingUploads());
    }

    public BuilderResources[] getResourcesArray() {
        return resources;
    }
}

