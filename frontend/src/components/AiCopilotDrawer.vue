<script setup lang="ts">
import { ref, watch } from 'vue';
import api from '../api/client';
import { useAuthStore } from '../stores/auth';
import type { Discrepancy, SimilarDiscrepancy, AiTriageResponse } from '../types';

const props = defineProps<{
  discrepancy: Discrepancy | null;
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'resolved', id: string): void;
}>();

const authStore = useAuthStore();

const similarList = ref<SimilarDiscrepancy[]>([]);
const loadingSimilar = ref(false);

const triageResult = ref<AiTriageResponse | null>(null);
const triaging = ref(false);
const triageError = ref<string | null>(null);

const resolveComment = ref('');
const resolving = ref(false);
const resolveError = ref<string | null>(null);

const fetchSimilar = async (id: string) => {
  loadingSimilar.value = true;
  try {
    const res = await api.get<SimilarDiscrepancy[]>(`/api/v1/recon/discrepancies/${id}/similar?limit=5`);
    similarList.value = res.data;
  } catch (err) {
    console.warn('Failed to load similar discrepancies', err);
    similarList.value = [];
  } finally {
    loadingSimilar.value = false;
  }
};

watch(
  () => props.discrepancy,
  (newVal) => {
    triageResult.value = null;
    triageError.value = null;
    resolveComment.value = '';
    resolveError.value = null;
    if (newVal) {
      fetchSimilar(newVal.id);
    } else {
      similarList.value = [];
    }
  },
  { immediate: true }
);

const runAiTriage = async () => {
  if (!props.discrepancy) return;
  triaging.value = true;
  triageError.value = null;
  try {
    const res = await api.post<AiTriageResponse>('/api/v1/ai/triage', {
      externalOrderId: props.discrepancy.externalOrderId,
    });
    triageResult.value = res.data;
  } catch (err: any) {
    triageError.value = err.response?.data?.message || err.message || 'AI Triage service unavailable';
  } finally {
    triaging.value = false;
  }
};

const handleResolve = async () => {
  if (!props.discrepancy || !resolveComment.value.trim()) return;
  resolving.value = true;
  resolveError.value = null;
  try {
    await api.post(`/api/v1/recon/discrepancies/${props.discrepancy.id}/resolve`, {
      comment: resolveComment.value.trim(),
    });
    emit('resolved', props.discrepancy.id);
    emit('close');
  } catch (err: any) {
    resolveError.value = err.response?.data?.message || err.message || 'Resolution failed';
  } finally {
    resolving.value = false;
  }
};
</script>

<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50 overflow-hidden"
    aria-labelledby="slide-over-title"
    role="dialog"
    aria-modal="true"
  >
    <!-- Backdrop -->
    <div
      class="absolute inset-0 bg-slate-950/70 backdrop-blur-sm transition-opacity"
      @click="emit('close')"
    ></div>

    <div class="fixed inset-y-0 right-0 max-w-full flex pl-10">
      <div class="w-screen max-w-2xl bg-slate-900 border-l border-slate-800 shadow-2xl flex flex-col">
        <!-- Header -->
        <div class="px-6 py-5 border-b border-slate-800 flex items-center justify-between bg-slate-900/50">
          <div class="flex items-center space-x-3">
            <span class="text-2xl">🤖</span>
            <div>
              <h2 class="text-lg font-bold text-slate-100" id="slide-over-title">
                AI Audit Copilot & Vector Clusters
              </h2>
              <p class="text-xs text-slate-400">
                Ground-truth root cause inference & cosine similarity clustering
              </p>
            </div>
          </div>
          <button
            @click="emit('close')"
            class="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition"
          >
            ✕
          </button>
        </div>

        <!-- Body -->
        <div class="flex-1 overflow-y-auto p-6 space-y-6" v-if="discrepancy">
          <!-- Discrepancy Overview Card -->
          <div class="p-4 rounded-xl bg-slate-800/70 border border-slate-700/60 space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-xs font-mono px-2 py-0.5 rounded bg-slate-700 text-slate-300">
                Order ID: {{ discrepancy.externalOrderId }}
              </span>
              <span
                class="text-xs px-2.5 py-0.5 rounded-full font-semibold"
                :class="
                  discrepancy.status === 'RESOLVED'
                    ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                    : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                "
              >
                {{ discrepancy.status }}
              </span>
            </div>
            <div>
              <span class="text-xs font-medium text-slate-400 uppercase tracking-wide">Type:</span>
              <div class="text-sm font-semibold text-amber-400 mt-0.5">{{ discrepancy.discrepancyType }}</div>
            </div>
            <div>
              <span class="text-xs font-medium text-slate-400 uppercase tracking-wide">Details:</span>
              <p class="text-sm text-slate-200 mt-1 whitespace-pre-wrap font-sans bg-slate-950/50 p-2.5 rounded border border-slate-800">
                {{ discrepancy.details }}
              </p>
            </div>
          </div>

          <!-- AI Triage Action & Output -->
          <div class="p-5 rounded-xl bg-gradient-to-b from-indigo-950/40 to-slate-900 border border-indigo-500/30 space-y-4">
            <div class="flex items-center justify-between">
              <div class="flex items-center space-x-2">
                <span class="w-2.5 h-2.5 rounded-full bg-indigo-400 animate-pulse"></span>
                <h3 class="text-sm font-bold text-indigo-300 tracking-wide uppercase">
                  Grounded AI Copilot Triage
                </h3>
              </div>
              <button
                v-if="authStore.isAuditor || authStore.isAdmin"
                @click="runAiTriage"
                :disabled="triaging"
                class="px-3.5 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600 hover:bg-indigo-500 text-white transition disabled:opacity-50 shadow-md shadow-indigo-600/20 flex items-center space-x-1.5"
              >
                <span v-if="triaging" class="animate-spin text-xs">⟳</span>
                <span>{{ triaging ? 'Analyzing...' : 'Run Copilot Triage' }}</span>
              </button>
              <span v-else class="text-xs text-slate-500 italic">Auditor role required</span>
            </div>

            <div v-if="triageError" class="p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
              {{ triageError }}
            </div>

            <div v-if="triageResult" class="space-y-3 mt-3 animate-fade-in">
              <div class="flex items-center space-x-2">
                <span class="text-xs text-slate-400">Root Cause Category:</span>
                <span class="text-xs px-2 py-0.5 rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30 font-mono">
                  {{ triageResult.rootCauseCategory }}
                </span>
              </div>
              <div class="p-3.5 rounded-lg bg-slate-950/80 border border-slate-800 space-y-2">
                <div class="text-xs font-semibold text-slate-300">Analysis:</div>
                <p class="text-xs text-slate-200 leading-relaxed font-sans">
                  {{ triageResult.analysis }}
                </p>
                <div class="text-xs font-semibold text-emerald-400 pt-2 border-t border-slate-800">
                  Recommended Action:
                </div>
                <p class="text-xs text-emerald-300 leading-relaxed font-sans">
                  {{ triageResult.recommendation }}
                </p>
              </div>
            </div>
            <div v-else-if="!triaging" class="text-xs text-slate-400 italic">
              Trigger triage to analyze 3-way reconciliation traces across Gateway, Ledger, and Bank Settlement records.
            </div>
          </div>

          <!-- Similar Discrepancies (pgvector Cosine Search) -->
          <div class="space-y-3">
            <div class="flex items-center justify-between">
              <div class="flex items-center space-x-2">
                <span class="text-base">🧬</span>
                <h3 class="text-sm font-semibold text-slate-200">
                  Historical Vector Neighbors (pgvector)
                </h3>
              </div>
              <span class="text-xs text-slate-400">{{ similarList.length }} matches</span>
            </div>

            <div v-if="loadingSimilar" class="text-xs text-slate-400 animate-pulse py-2">
              Querying pgvector cosine similarity clusters...
            </div>

            <div v-else-if="similarList.length === 0" class="text-xs text-slate-500 italic py-2">
              No historical matches found above similarity threshold.
            </div>

            <div v-else class="space-y-2">
              <div
                v-for="item in similarList"
                :key="item.id"
                class="p-3 rounded-lg bg-slate-800/40 border border-slate-700/50 hover:bg-slate-800/80 transition space-y-1.5"
              >
                <div class="flex items-center justify-between">
                  <span class="text-xs font-mono text-slate-300">{{ item.externalOrderId }}</span>
                  <div class="flex items-center space-x-1.5">
                    <span class="text-xs text-slate-400">Cosine:</span>
                    <span class="text-xs font-mono font-bold text-emerald-400">
                      {{ (item.similarityScore * 100).toFixed(1) }}%
                    </span>
                  </div>
                </div>
                <div class="text-xs font-medium text-amber-300">{{ item.discrepancyType }}</div>
                <p class="text-xs text-slate-400 truncate">{{ item.details }}</p>
              </div>
            </div>
          </div>

          <!-- Admin Resolution Section -->
          <div v-if="authStore.isAdmin && discrepancy.status !== 'RESOLVED'" class="p-4 rounded-xl bg-slate-800/50 border border-slate-700/60 space-y-3">
            <h4 class="text-xs font-bold text-slate-200 uppercase tracking-wide">
              Admin Manual Resolution
            </h4>
            <div v-if="resolveError" class="p-2 rounded bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
              {{ resolveError }}
            </div>
            <textarea
              v-model="resolveComment"
              rows="2"
              placeholder="Enter audit resolution reason or ledger offset reference..."
              class="w-full text-xs bg-slate-950 border border-slate-700 rounded-lg p-2.5 text-slate-200 focus:outline-none focus:border-emerald-500"
            ></textarea>
            <div class="flex justify-end">
              <button
                @click="handleResolve"
                :disabled="resolving || !resolveComment.trim()"
                class="px-4 py-1.5 rounded-lg text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white transition disabled:opacity-50"
              >
                {{ resolving ? 'Resolving...' : 'Confirm Discrepancy Resolution' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
