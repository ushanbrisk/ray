"""Implements Distributed Prioritized Experience Replay.

https://arxiv.org/abs/1803.00933"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import os
import random
import time
import threading

import numpy as np
from six.moves import queue

import ray
from ray.rllib.optimizers.policy_optimizer import PolicyOptimizer
from ray.rllib.optimizers.sample_batch import SampleBatch
from ray.rllib.utils.actors import TaskPool, create_colocated
from ray.rllib.utils.timer import TimerStat
from ray.rllib.utils.window_stat import WindowStat


SAMPLE_QUEUE_DEPTH = 2
REPLAY_QUEUE_DEPTH = 4
LEARNER_QUEUE_MAX_SIZE = 16


class LearnerThread(threading.Thread):
    """Background thread that updates the local model from sampled trajectories.

    The learner thread communicates with the main thread through Queues. This
    is needed since Ray operations can only be run on the main thread. In
    addition, moving heavyweight gradient ops session runs off the main thread
    improves overall throughput.
    """

    def __init__(self, local_evaluator):
        threading.Thread.__init__(self)
        self.learner_queue_size = WindowStat("size", 50)
        self.local_evaluator = local_evaluator
        self.inqueue = queue.Queue(maxsize=LEARNER_QUEUE_MAX_SIZE)
        self.outqueue = queue.Queue()
        self.queue_timer = TimerStat()
        self.grad_timer = TimerStat()
        self.daemon = True
        self.weights_updated = False

    def run(self):
        while True:
            self.step()

    def step(self):
        with self.queue_timer:
            ra, replay = self.inqueue.get()
        if replay is not None:
            with self.grad_timer:
                self.local_evaluator.compute_apply(replay)
            self.outqueue.put(True)
        self.learner_queue_size.push(self.inqueue.qsize())
        self.weights_updated = True


class ImpalaOptimizer(PolicyOptimizer):
    """Main event loop of the Impala optimizer.

    This class coordinates the data transfers between the learner thread
    and remote evaluators (Impala actors).

    This optimizer requires that policy evaluators return an additional
    "vtrace importance weight" array in the info return of compute_gradients(). This weight
    term will be used for sample re-weighting."""

    def _init(
            self, train_batch_size=512, sample_batch_size=50,
            max_weight_sync_delay=400,
            clip_rewards=True, debug=False):

        self.debug = debug
        self.learning_started = False
        self.train_batch_size = train_batch_size
        self.sample_batch_size = sample_batch_size
        self.max_weight_sync_delay = max_weight_sync_delay

        self.learner = LearnerThread(self.local_evaluator)
        self.learner.start()

        assert len(self.remote_evaluators) > 0

        # Stats
        self.timers = {k: TimerStat() for k in [
            "put_weights", "enqueue", "sample_processing",
            "train", "sample"]}
        self.num_weight_syncs = 0
        self.learning_started = False

        # Kick off async background sampling
        self.sample_tasks = TaskPool()
        weights = self.local_evaluator.get_weights()
        for ev in self.remote_evaluators:
            ev.set_weights.remote(weights)
            for _ in range(SAMPLE_QUEUE_DEPTH):
                self.sample_tasks.add(ev, ev.sample.remote())

    def step(self):
        start = time.time()
        sample_timesteps, train_timesteps = self._step()
        time_delta = time.time() - start
        self.timers["sample"].push(time_delta)
        self.timers["sample"].push_units_processed(sample_timesteps)
        if train_timesteps > 0:
            self.learning_started = True
        if self.learning_started:
            self.timers["train"].push(time_delta)
            self.timers["train"].push_units_processed(train_timesteps)
        self.num_steps_sampled += sample_timesteps
        self.num_steps_trained += train_timesteps

    def _step(self):
        sample_timesteps, train_timesteps = 0, 0
        weights = None

        with self.timers["sample_processing"]:
            for ev, sample_batch in self.sample_tasks.completed():
                sample_timesteps += self.sample_batch_size

                with self.timers["enqueue"]:
                    self.learner.inqueue.put((ev, samples))

                # Note that it's important to pull new weights once
                # updated to avoid excessive correlation between actors
                if weights is None or self.learner.weights_updated:
                    self.learner.weights_updated = False
                    with self.timers["put_weights"]:
                        weights = ray.put(
                            self.local_evaluator.get_weights())
                ev.set_weights.remote(weights)
                self.num_weight_syncs += 1

                # Kick off another sample request
                self.sample_tasks.add(ev, ev.sample.remote())

        while not self.learner.outqueue.empty():
            flag  = self.learner.outqueue.get()
            train_timesteps += self.train_batch_size

        return sample_timesteps, train_timesteps

    def stats(self):
        replay_stats = ray.get(self.replay_actors[0].stats.remote())
        timing = {
            "{}_time_ms".format(k): round(1000 * self.timers[k].mean, 3)
            for k in self.timers
        }
        timing["learner_grad_time_ms"] = round(
            1000 * self.learner.grad_timer.mean, 3)
        timing["learner_dequeue_time_ms"] = round(
            1000 * self.learner.queue_timer.mean, 3)
        stats = {
            "sample_throughput": round(
                self.timers["sample"].mean_throughput, 3),
            "train_throughput": round(self.timers["train"].mean_throughput, 3),
            "num_weight_syncs": self.num_weight_syncs,
        }
        debug_stats = {
            "replay_shard_0": replay_stats,
            "timing_breakdown": timing,
            "pending_sample_tasks": self.sample_tasks.count,
            "pending_replay_tasks": self.replay_tasks.count,
            "learner_queue": self.learner.learner_queue_size.stats(),
        }
        if self.debug:
            stats.update(debug_stats)
        return dict(PolicyOptimizer.stats(self), **stats)
