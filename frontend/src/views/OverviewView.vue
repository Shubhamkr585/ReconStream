<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import api from '../api/client';
import AiCopilotDrawer from '../components/AiCopilotDrawer.vue';
import type { Discrepancy, PageResponse } from '../types';

const router = useRouter();

interface ReconSummaryData {
  discrepancies: Record<string, { count: number; totalDiffPaise: number }>;
  accountBalances: Record<string, number>;
  totalDiscrepancies: number;
  totalDiscrepancyAmountPaise: number;
}

interface AccountSummaryData {
  accountCode: string;
  accountName: string;
  accountType: string;
  totalDebitPaise: number;
  totalCreditPaise: number;
  netBalancePaise: number;
}

const summary = ref<ReconSummaryData | null>(null);
const accounts = ref<AccountSummaryData[]>([]);
const loadingSummary = ref(false);

// Discrepancy table state
const discrepancies = ref<Discrepancy[]>([]);
const totalElements = ref(0);
const totalPages = ref(0);
const currentPage = ref(0);
const pageSize = ref(10);
const filterType = ref('');
const filterStatus = ref('');
const loadingDiscrepancies = ref(false);

// Drawer state
const selectedDiscrepancy = ref<Discrepancy | null>(null);
const isDrawerOpen = ref(false);

const formatRupees = (paise: number = 0) => {
  const abs = Math.abs(paise) / 100;
  const formatted = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
  }).format(abs);
  return paise < 0 ? `-${formatted}` : formatted;
};

const fetchSummaryAndAccounts = async () => {
  loadingSummary.value = true;
  try {
    const [summaryRes, accountsRes] = await Promise.all([
      api.get<ReconSummaryData>('/api/v1/recon/summary'),
      api.get<AccountSummaryData[]>('/api/v1/ledger/accounts/summary'),
    ]);
    summary.value = summaryRes.data;
    accounts.value = accountsRes.data;
  } catch (err) {
    console.warn('Failed to load summary stats', err);
  } finally {
    loadingSummary.value = false;
  }
};

const fetchDiscrepancies = async () => {
  loadingDiscrepancies.value = true;
  try {
    const params: Record<string, any> = {
      page: currentPage.value,
      size: pageSize.value,
    };
    if (filterType.value) params.type = filterType.value;
    if (filterStatus.value) params.status = filterStatus.value;

    const res = await api.get<PageResponse<Discrepancy>>('/api/v1/recon/discrepancies', { params });
    discrepancies.value = res.data.content;
    totalElements.value = res.data.totalElements;
    totalPages.value = res.data.totalPages;
  } catch (err) {
    console.warn('Failed to load discrepancies', err);
  } finally {
    loadingDiscrepancies.value = false;
  }
};

const openCopilot = (item: Discrepancy) => {
  selectedDiscrepancy.value = item;
  isDrawerOpen.value = true;
};

const inspectLedger = (orderId: string) => {
  router.push({ path: '/ledger', query: { orderId } });
};

const onDiscrepancyResolved = (id: string) => {
  fetchDiscrepancies();
  fetchSummaryAndAccounts();
};

watch([filterType, filterStatus], () => {
  currentPage.value = 0;
  fetchDiscrepancies();
});

watch(currentPage, () => {
  fetchDiscrepancies();
});

onMounted(() => {
  fetchSummaryAndAccounts();
  fetchDiscrepancies();
});
</script>

<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
    <!-- Header -->
    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold text-white tracking-tight">Overview & Live Discrepancy Feed</h1>
        <p class="text-sm text-slate-400">
          Continuous 3-way reconciliation across Payment Gateway, Internal Ledger, and Bank Settlement
        </p>
      </div>
      <button
        @click="() => { fetchSummaryAndAccounts(); fetchDiscrepancies(); }"
        class="inline-flex items-center space-x-2 px-3.5 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium border border-slate-700 transition"
      >
        <span>🔄 Refresh Feed</span>
      </button>
    </div>

    <!-- Live KPI Metric Cards -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <div class="p-5 rounded-xl bg-slate-900 border border-slate-800 shadow-sm space-y-1">
        <div class="text-xs font-semibold text-slate-400 uppercase tracking-wide">Total Discrepancies</div>
        <div class="text-3xl font-extrabold text-white">
          {{ summary?.totalDiscrepancies ?? 0 }}
        </div>
        <div class="text-xs text-rose-400 flex items-center space-x-1 pt-1">
          <span>⚠️ Active exceptions requiring review</span>
        </div>
      </div>

      <div class="p-5 rounded-xl bg-slate-900 border border-slate-800 shadow-sm space-y-1">
        <div class="text-xs font-semibold text-slate-400 uppercase tracking-wide">Discrepancy Exposure</div>
        <div class="text-3xl font-extrabold text-amber-400">
          {{ formatRupees(summary?.totalDiscrepancyAmountPaise) }}
        </div>
        <div class="text-xs text-slate-400 pt-1">Cumulative variance exposure</div>
      </div>

      <div class="p-5 rounded-xl bg-slate-900 border border-slate-800 shadow-sm space-y-1">
        <div class="text-xs font-semibold text-slate-400 uppercase tracking-wide">Amount Mismatches</div>
        <div class="text-3xl font-extrabold text-cyan-400">
          {{ summary?.discrepancies?.['AMOUNT_MISMATCH']?.count ?? 0 }}
        </div>
        <div class="text-xs text-slate-400 pt-1">Gateway vs Bank fee variances</div>
      </div>

      <div class="p-5 rounded-xl bg-slate-900 border border-slate-800 shadow-sm space-y-1">
        <div class="text-xs font-semibold text-slate-400 uppercase tracking-wide">Unmatched Records</div>
        <div class="text-3xl font-extrabold text-indigo-400">
          {{ (summary?.discrepancies?.['UNMATCHED_ORDER']?.count ?? 0) + (summary?.discrepancies?.['UNMATCHED_SETTLEMENT']?.count ?? 0) }}
        </div>
        <div class="text-xs text-slate-400 pt-1">Missing settlement or order legs</div>
      </div>
    </div>

    <!-- 4-Account Double-Entry Balances -->
    <div class="space-y-3">
      <div class="flex items-center space-x-2">
        <span class="text-lg">⚖️</span>
        <h2 class="text-base font-bold text-slate-200">Double-Entry Account Balances</h2>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div
          v-for="acc in accounts"
          :key="acc.accountCode"
          class="p-4 rounded-xl bg-slate-900/80 border border-slate-800 space-y-2 hover:border-slate-700 transition"
        >
          <div class="flex items-center justify-between">
            <span class="text-xs font-mono font-bold text-slate-300">{{ acc.accountCode }}</span>
            <span class="text-[10px] px-2 py-0.5 rounded font-mono uppercase bg-slate-800 text-slate-400">
              {{ acc.accountType }}
            </span>
          </div>
          <div class="text-sm font-semibold text-slate-200">{{ acc.accountName }}</div>
          <div class="text-xl font-bold text-emerald-400">
            {{ formatRupees(acc.netBalancePaise) }}
          </div>
          <div class="pt-2 border-t border-slate-800/80 flex justify-between text-[11px] text-slate-400 font-mono">
            <span>Dr: {{ formatRupees(acc.totalDebitPaise) }}</span>
            <span>Cr: {{ formatRupees(acc.totalCreditPaise) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Server-Side Paginated Discrepancy Table -->
    <div class="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-4 shadow-xl">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <h3 class="text-lg font-bold text-slate-100">Discrepancy Exception Queue</h3>
          <p class="text-xs text-slate-400">Filtered live exceptions with AI cluster assistance</p>
        </div>

        <!-- Filter Controls -->
        <div class="flex items-center space-x-3">
          <select
            v-model="filterType"
            class="bg-slate-950 border border-slate-700 rounded-lg text-xs px-3 py-1.5 text-slate-200 focus:outline-none focus:border-emerald-500"
          >
            <option value="">All Types</option>
            <option value="AMOUNT_MISMATCH">Amount Mismatch</option>
            <option value="UNMATCHED_ORDER">Unmatched Order</option>
            <option value="UNMATCHED_SETTLEMENT">Unmatched Settlement</option>
            <option value="FEE_MISMATCH">Fee Mismatch</option>
          </select>

          <select
            v-model="filterStatus"
            class="bg-slate-950 border border-slate-700 rounded-lg text-xs px-3 py-1.5 text-slate-200 focus:outline-none focus:border-emerald-500"
          >
            <option value="">All Statuses</option>
            <option value="PENDING">Pending</option>
            <option value="RESOLVED">Resolved</option>
            <option value="ESCALATED">Escalated</option>
          </select>
        </div>
      </div>

      <!-- Table -->
      <div class="overflow-x-auto">
        <table class="w-full text-left text-xs text-slate-300">
          <thead class="text-[11px] uppercase tracking-wider text-slate-400 bg-slate-950/60 border-y border-slate-800">
            <tr>
              <th class="py-3 px-4">Order Reference</th>
              <th class="py-3 px-4">Type</th>
              <th class="py-3 px-4">Status</th>
              <th class="py-3 px-4">Exception Details</th>
              <th class="py-3 px-4">Detected At</th>
              <th class="py-3 px-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-800">
            <tr v-if="loadingDiscrepancies">
              <td colspan="6" class="py-8 text-center text-slate-400 italic">
                Loading exception records...
              </td>
            </tr>
            <tr v-else-if="discrepancies.length === 0">
              <td colspan="6" class="py-8 text-center text-slate-500 italic">
                No discrepancies found matching criteria. System balanced.
              </td>
            </tr>
            <tr
              v-else
              v-for="item in discrepancies"
              :key="item.id"
              class="hover:bg-slate-800/40 transition group"
            >
              <td class="py-3.5 px-4 font-mono font-semibold text-slate-200">
                {{ item.externalOrderId }}
              </td>
              <td class="py-3.5 px-4">
                <span class="px-2 py-0.5 rounded font-mono text-[10px] bg-amber-500/10 text-amber-300 border border-amber-500/20">
                  {{ item.discrepancyType }}
                </span>
              </td>
              <td class="py-3.5 px-4">
                <span
                  class="px-2 py-0.5 rounded-full text-[10px] font-semibold"
                  :class="
                    item.status === 'RESOLVED'
                      ? 'bg-emerald-500/20 text-emerald-400'
                      : 'bg-rose-500/20 text-rose-400'
                  "
                >
                  {{ item.status }}
                </span>
              </td>
              <td class="py-3.5 px-4 max-w-xs truncate text-slate-300" :title="item.details">
                {{ item.details }}
              </td>
              <td class="py-3.5 px-4 text-slate-400 text-[11px]">
                {{ new Date(item.createdAt).toLocaleDateString() }} {{ new Date(item.createdAt).toLocaleTimeString() }}
              </td>
              <td class="py-3.5 px-4 text-right space-x-2">
                <button
                  @click="openCopilot(item)"
                  class="px-2.5 py-1 rounded bg-indigo-600/30 hover:bg-indigo-600/60 text-indigo-300 border border-indigo-500/30 text-[11px] font-medium transition"
                >
                  🤖 Copilot
                </button>
                <button
                  @click="inspectLedger(item.externalOrderId)"
                  class="px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 text-[11px] font-medium transition"
                >
                  Ledger
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div class="flex items-center justify-between pt-4 border-t border-slate-800 text-xs text-slate-400">
        <div>
          Showing page {{ currentPage + 1 }} of {{ Math.max(1, totalPages) }} ({{ totalElements }} records)
        </div>
        <div class="flex space-x-2">
          <button
            @click="currentPage--"
            :disabled="currentPage === 0"
            class="px-3 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 disabled:opacity-40 disabled:cursor-not-allowed transition"
          >
            Previous
          </button>
          <button
            @click="currentPage++"
            :disabled="currentPage >= totalPages - 1"
            class="px-3 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 disabled:opacity-40 disabled:cursor-not-allowed transition"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <!-- AI Copilot Slide-Out Drawer -->
    <AiCopilotDrawer
      :is-open="isDrawerOpen"
      :discrepancy="selectedDiscrepancy"
      @close="isDrawerOpen = false"
      @resolved="onDiscrepancyResolved"
    />
  </div>
</template>
