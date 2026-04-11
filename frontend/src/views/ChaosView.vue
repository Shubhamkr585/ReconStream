<script setup lang="ts">
import { ref } from 'vue';
import api from '../api/client';
import { useAuthStore } from '../stores/auth';
import type { ChaosRunResponse } from '../types';

const authStore = useAuthStore();

// Settlement CSV upload state
const selectedFile = ref<File | null>(null);
const uploadingCsv = ref(false);
const csvUploadResult = ref<{ filename: string; totalRows: number; successfullyQueued: number } | null>(null);
const csvUploadError = ref<string | null>(null);

// Chaos simulator state
const orderCount = ref(20);
const duplicateRate = ref(0.20);
const outOfOrderRate = ref(0.15);
const dropRate = ref(0.10);
const runningChaos = ref(false);
const chaosResult = ref<ChaosRunResponse | null>(null);
const chaosError = ref<string | null>(null);

const onFileChange = (e: Event) => {
  const target = e.target as HTMLInputElement;
  if (target.files && target.files.length > 0) {
    selectedFile.value = target.files[0];
    csvUploadResult.value = null;
    csvUploadError.value = null;
  }
};

const handleCsvUpload = async () => {
  if (!selectedFile.value) return;

  uploadingCsv.value = true;
  csvUploadError.value = null;
  csvUploadResult.value = null;

  const formData = new FormData();
  formData.append('file', selectedFile.value);

  try {
    const res = await api.post('/api/v1/recon/settlements/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    csvUploadResult.value = res.data;
  } catch (err: any) {
    csvUploadError.value = err.response?.data?.message || err.message || 'Settlement CSV upload failed';
  } finally {
    uploadingCsv.value = false;
  }
};

const handleRunChaos = async () => {
  runningChaos.value = true;
  chaosError.value = null;
  chaosResult.value = null;

  try {
    const res = await api.post<ChaosRunResponse>('/api/v1/simulator/chaos-run', {
      orderCount: Number(orderCount.value),
      duplicateRate: Number(duplicateRate.value),
      outOfOrderRate: Number(outOfOrderRate.value),
      dropRate: Number(dropRate.value),
    });
    chaosResult.value = res.data;
  } catch (err: any) {
    chaosError.value = err.response?.data?.message || err.message || 'Chaos simulator run failed';
  } finally {
    runningChaos.value = false;
  }
};
</script>

<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
    <!-- Header -->
    <div class="space-y-1">
      <h1 class="text-2xl font-bold text-white tracking-tight">Settlement Upload & Chaos Simulator Lab</h1>
      <p class="text-sm text-slate-400">
        Ingest external bank settlement files and stress-test the transactional outbox and out-of-order state machine
      </p>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
      <!-- Settlement CSV Upload Card -->
      <div class="p-6 rounded-2xl bg-slate-900 border border-slate-800 shadow-xl space-y-6">
        <div class="flex items-center space-x-3">
          <div class="w-10 h-10 rounded-xl bg-cyan-500/10 border border-cyan-500/30 flex items-center justify-center text-cyan-400 text-lg">
            📂
          </div>
          <div>
            <h2 class="text-lg font-bold text-slate-100">Bank Settlement Ingestion</h2>
            <p class="text-xs text-slate-400">Upload bank CSV records to trigger automated 3-way recon</p>
          </div>
        </div>

        <div v-if="authStore.isAnalyst" class="p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center space-x-2">
          <span>🔒</span>
          <span>Analyst role has Read-Only permissions. Auditor or Admin credentials required to upload CSVs.</span>
        </div>

        <div class="space-y-4">
          <div class="border-2 border-dashed border-slate-700 hover:border-slate-600 rounded-xl p-6 text-center bg-slate-950/40">
            <input
              type="file"
              accept=".csv"
              @change="onFileChange"
              :disabled="authStore.isAnalyst"
              id="settlementFile"
              class="hidden"
            />
            <label
              for="settlementFile"
              class="cursor-pointer flex flex-col items-center justify-center space-y-2"
              :class="{ 'opacity-50 cursor-not-allowed': authStore.isAnalyst }"
            >
              <span class="text-3xl">📄</span>
              <span class="text-xs font-semibold text-slate-200">
                {{ selectedFile ? selectedFile.name : 'Click or drop settlement CSV here' }}
              </span>
              <span class="text-[11px] text-slate-400">
                CSV header: external_order_id, bank_ref_id, settlement_date, gross_amount_paise, mdr_fee_paise, net_amount_paise, status
              </span>
            </label>
          </div>

          <div v-if="csvUploadError" class="p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
            {{ csvUploadError }}
          </div>

          <div v-if="csvUploadResult" class="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-300 text-xs space-y-1">
            <div class="font-bold flex items-center space-x-1.5">
              <span>✓</span>
              <span>Settlement CSV Queued for Asynchronous Reconciliation</span>
            </div>
            <div class="text-slate-300">File: <span class="font-mono">{{ csvUploadResult.filename }}</span></div>
            <div class="text-slate-300">Total Rows Processed: <span class="font-mono font-bold">{{ csvUploadResult.totalRows }}</span></div>
          </div>

          <button
            @click="handleCsvUpload"
            :disabled="!selectedFile || uploadingCsv || authStore.isAnalyst"
            class="w-full py-2.5 px-4 rounded-lg text-xs font-semibold bg-cyan-600 hover:bg-cyan-500 text-white transition disabled:opacity-50 shadow-md shadow-cyan-600/20"
          >
            {{ uploadingCsv ? 'Streaming & Parsing CSV...' : 'Ingest & Trigger Reconciliation' }}
          </button>
        </div>
      </div>

      <!-- Real Chaos PSP Simulator Card -->
      <div class="p-6 rounded-2xl bg-slate-900 border border-slate-800 shadow-xl space-y-6">
        <div class="flex items-center space-x-3">
          <div class="w-10 h-10 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-center justify-center text-rose-400 text-lg">
            🌪️
          </div>
          <div>
            <h2 class="text-lg font-bold text-slate-100">Chaos PSP Simulator</h2>
            <p class="text-xs text-slate-400">Inject out-of-order arrivals, duplicate webhooks, and dropped events</p>
          </div>
        </div>

        <div v-if="!authStore.isAdmin" class="p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center space-x-2">
          <span>🔒</span>
          <span>Admin role required to trigger Chaos PSP simulations. Current role: {{ authStore.role || 'Unauthenticated' }}.</span>
        </div>

        <div class="space-y-4">
          <div class="grid grid-cols-2 gap-4">
            <div>
              <label class="block text-xs font-medium text-slate-300 mb-1">
                Order Count: <span class="font-bold text-white">{{ orderCount }}</span>
              </label>
              <input
                v-model.number="orderCount"
                type="number"
                min="5"
                max="100"
                :disabled="!authStore.isAdmin"
                class="w-full px-3 py-1.5 bg-slate-950 border border-slate-700 rounded-lg text-xs text-white focus:outline-none focus:border-rose-500"
              />
            </div>

            <div>
              <label class="block text-xs font-medium text-slate-300 mb-1">
                Duplicate Rate: <span class="font-bold text-white">{{ Math.round(duplicateRate * 100) }}%</span>
              </label>
              <input
                v-model.number="duplicateRate"
                type="range"
                min="0"
                max="0.5"
                step="0.05"
                :disabled="!authStore.isAdmin"
                class="w-full accent-rose-500"
              />
            </div>

            <div>
              <label class="block text-xs font-medium text-slate-300 mb-1">
                Out-Of-Order Rate: <span class="font-bold text-white">{{ Math.round(outOfOrderRate * 100) }}%</span>
              </label>
              <input
                v-model.number="outOfOrderRate"
                type="range"
                min="0"
                max="0.5"
                step="0.05"
                :disabled="!authStore.isAdmin"
                class="w-full accent-rose-500"
              />
            </div>

            <div>
              <label class="block text-xs font-medium text-slate-300 mb-1">
                Drop Rate: <span class="font-bold text-white">{{ Math.round(dropRate * 100) }}%</span>
              </label>
              <input
                v-model.number="dropRate"
                type="range"
                min="0"
                max="0.5"
                step="0.05"
                :disabled="!authStore.isAdmin"
                class="w-full accent-rose-500"
              />
            </div>
          </div>

          <div v-if="chaosError" class="p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
            {{ chaosError }}
          </div>

          <!-- Simulation Live Stats -->
          <div v-if="chaosResult" class="p-4 rounded-xl bg-slate-950/80 border border-slate-800 space-y-3">
            <div class="text-xs font-bold text-rose-300 uppercase tracking-wider flex items-center space-x-1.5">
              <span>⚡</span>
              <span>Chaos Run Completed</span>
            </div>
            <div class="grid grid-cols-2 gap-2 text-xs">
              <div class="bg-slate-900 p-2 rounded border border-slate-800">
                <div class="text-slate-400 text-[10px]">Orders Generated</div>
                <div class="text-base font-bold text-white font-mono">{{ chaosResult.ordersGenerated }}</div>
              </div>
              <div class="bg-slate-900 p-2 rounded border border-slate-800">
                <div class="text-slate-400 text-[10px]">Webhooks Dispatched</div>
                <div class="text-base font-bold text-cyan-400 font-mono">{{ chaosResult.webhooksSent }}</div>
              </div>
              <div class="bg-slate-900 p-2 rounded border border-slate-800">
                <div class="text-slate-400 text-[10px]">Duplicate Retries</div>
                <div class="text-base font-bold text-amber-400 font-mono">{{ chaosResult.duplicatesSent }}</div>
              </div>
              <div class="bg-slate-900 p-2 rounded border border-slate-800">
                <div class="text-slate-400 text-[10px]">Out-of-Order CAPTUREs</div>
                <div class="text-base font-bold text-rose-400 font-mono">{{ chaosResult.outOfOrderSent }}</div>
              </div>
            </div>
          </div>

          <button
            @click="handleRunChaos"
            :disabled="runningChaos || !authStore.isAdmin"
            class="w-full py-2.5 px-4 rounded-lg text-xs font-semibold bg-rose-600 hover:bg-rose-500 text-white transition disabled:opacity-50 shadow-md shadow-rose-600/20"
          >
            {{ runningChaos ? 'Firing Chaos Payload Streams...' : 'Execute Chaos Simulation' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
