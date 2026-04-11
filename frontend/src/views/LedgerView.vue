<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import api from '../api/client';

const route = useRoute();

interface LedgerEntryItem {
  id: string;
  referenceId: string;
  eventType: string;
  accountCode: string;
  accountName: string;
  direction: 'DEBIT' | 'CREDIT';
  amountPaise: number;
  createdAt: string;
}

interface OrderInspectionData {
  externalOrderId: string;
  paymentOrderId: string;
  totalDebitPaise: number;
  totalCreditPaise: number;
  driftPaise: number;
  balanced: boolean;
  entries: LedgerEntryItem[];
}

const searchOrderId = ref('');
const inspection = ref<OrderInspectionData | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const formatRupees = (paise: number = 0) => {
  const abs = Math.abs(paise) / 100;
  const formatted = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
  }).format(abs);
  return paise < 0 ? `-${formatted}` : formatted;
};

const handleSearch = async () => {
  const q = searchOrderId.value.trim();
  if (!q) return;

  loading.value = true;
  error.value = null;
  inspection.value = null;

  try {
    const res = await api.get<OrderInspectionData>(`/api/v1/ledger/order/${encodeURIComponent(q)}`);
    inspection.value = res.data;
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Order not found in ledger records';
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  const q = (route.query.orderId as string) || '';
  if (q) {
    searchOrderId.value = q;
    handleSearch();
  }
});
</script>

<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
    <!-- Header -->
    <div class="space-y-1">
      <h1 class="text-2xl font-bold text-white tracking-tight">Double-Entry Ledger Inspector</h1>
      <p class="text-sm text-slate-400">
        Verify strict double-entry balance invariants (Σ Debits − Σ Credits = 0) per transaction reference
      </p>
    </div>

    <!-- Search Form -->
    <div class="p-6 rounded-2xl bg-slate-900 border border-slate-800 shadow-xl space-y-4">
      <form @submit.prevent="handleSearch" class="flex flex-col sm:flex-row gap-3">
        <div class="flex-1 relative">
          <input
            v-model="searchOrderId"
            type="text"
            placeholder="Search by External Order ID (e.g. order-storm-0, ORD-12345)..."
            class="w-full px-4 py-2.5 bg-slate-950 border border-slate-700 rounded-lg text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-emerald-500 font-mono transition"
          />
        </div>
        <button
          type="submit"
          :disabled="loading || !searchOrderId.trim()"
          class="px-6 py-2.5 rounded-lg text-sm font-semibold bg-emerald-600 hover:bg-emerald-500 text-white transition disabled:opacity-50 shadow-md shadow-emerald-600/20"
        >
          {{ loading ? 'Inspecting...' : 'Audit Ledger Legs' }}
        </button>
      </form>

      <div v-if="error" class="p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
        {{ error }}
      </div>
    </div>

    <!-- Inspection Output -->
    <div v-if="inspection" class="space-y-6">
      <!-- Invariant Badge Card -->
      <div
        class="p-6 rounded-2xl border shadow-xl flex flex-col md:flex-row md:items-center md:justify-between gap-6"
        :class="
          inspection.balanced
            ? 'bg-emerald-950/20 border-emerald-500/40'
            : 'bg-rose-950/20 border-rose-500/40'
        "
      >
        <div class="space-y-2">
          <div class="flex items-center space-x-3">
            <span
              class="px-3 py-1 rounded-full text-xs font-bold tracking-wide border flex items-center space-x-1.5"
              :class="
                inspection.balanced
                  ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
                  : 'bg-rose-500/20 text-rose-300 border-rose-500/30'
              "
            >
              <span>{{ inspection.balanced ? '✓ ZERO DRIFT' : '⚠️ DRIFT DETECTED' }}</span>
              <span>— Invariant {{ inspection.balanced ? 'Maintained' : 'Violated' }}</span>
            </span>
            <span class="text-xs font-mono text-slate-400">Order: {{ inspection.externalOrderId }}</span>
          </div>

          <div class="text-xl font-bold text-white flex items-center space-x-2">
            <span>Σ Debit − Σ Credit =</span>
            <span
              class="font-mono"
              :class="inspection.balanced ? 'text-emerald-400' : 'text-rose-400'"
            >
              {{ formatRupees(inspection.driftPaise) }}
            </span>
          </div>
          <p class="text-xs text-slate-400">
            Internal Payment ID: <span class="font-mono text-slate-300">{{ inspection.paymentOrderId }}</span>
          </p>
        </div>

        <div class="flex items-center space-x-6 border-t md:border-t-0 md:border-l border-slate-800 pt-4 md:pt-0 md:pl-8">
          <div>
            <div class="text-[11px] font-mono text-slate-400 uppercase tracking-wider">Total Debits</div>
            <div class="text-lg font-bold font-mono text-indigo-400">
              {{ formatRupees(inspection.totalDebitPaise) }}
            </div>
          </div>
          <div>
            <div class="text-[11px] font-mono text-slate-400 uppercase tracking-wider">Total Credits</div>
            <div class="text-lg font-bold font-mono text-violet-400">
              {{ formatRupees(inspection.totalCreditPaise) }}
            </div>
          </div>
        </div>
      </div>

      <!-- Ledger Legs Table -->
      <div class="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-4 shadow-xl">
        <div class="flex items-center justify-between">
          <h3 class="text-base font-bold text-slate-100">
            Immutable Double-Entry Ledger Legs ({{ inspection.entries.length }})
          </h3>
          <span class="text-xs text-slate-400 font-mono">
            {{ inspection.entries.length }} postings recorded
          </span>
        </div>

        <div class="overflow-x-auto">
          <table class="w-full text-left text-xs text-slate-300">
            <thead class="text-[11px] uppercase tracking-wider text-slate-400 bg-slate-950/60 border-y border-slate-800">
              <tr>
                <th class="py-3 px-4">Event Type</th>
                <th class="py-3 px-4">Account Code</th>
                <th class="py-3 px-4">Direction</th>
                <th class="py-3 px-4 text-right">Amount (₹)</th>
                <th class="py-3 px-4 text-right">Amount (Paise)</th>
                <th class="py-3 px-4">Timestamp</th>
                <th class="py-3 px-4 font-mono text-slate-500">Entry ID</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-800">
              <tr
                v-for="entry in inspection.entries"
                :key="entry.id"
                class="hover:bg-slate-800/40 transition font-sans"
              >
                <td class="py-3.5 px-4 font-mono font-semibold text-slate-200">
                  <span class="px-2 py-0.5 rounded bg-slate-800 border border-slate-700 text-[10px]">
                    {{ entry.eventType }}
                  </span>
                </td>
                <td class="py-3.5 px-4 font-mono font-medium text-slate-200">
                  {{ entry.accountCode }}
                </td>
                <td class="py-3.5 px-4">
                  <span
                    class="px-2.5 py-0.5 rounded text-[10px] font-bold font-mono"
                    :class="
                      entry.direction === 'DEBIT'
                        ? 'bg-indigo-500/20 text-indigo-300 border border-indigo-500/30'
                        : 'bg-violet-500/20 text-violet-300 border border-violet-500/30'
                    "
                  >
                    {{ entry.direction }}
                  </span>
                </td>
                <td
                  class="py-3.5 px-4 text-right font-mono font-bold"
                  :class="entry.direction === 'DEBIT' ? 'text-indigo-400' : 'text-violet-400'"
                >
                  {{ formatRupees(entry.amountPaise) }}
                </td>
                <td class="py-3.5 px-4 text-right font-mono text-slate-400">
                  {{ entry.amountPaise.toLocaleString() }}
                </td>
                <td class="py-3.5 px-4 text-slate-400 text-[11px]">
                  {{ new Date(entry.createdAt).toLocaleDateString() }} {{ new Date(entry.createdAt).toLocaleTimeString() }}
                </td>
                <td class="py-3.5 px-4 font-mono text-[10px] text-slate-500 truncate max-w-[120px]" :title="entry.id">
                  {{ entry.id }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
