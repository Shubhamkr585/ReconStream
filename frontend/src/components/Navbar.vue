<script setup lang="ts">
import { computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';

const authStore = useAuthStore();
const router = useRouter();
const route = useRoute();

const user = computed(() => authStore.user);
const role = computed(() => authStore.role);

const roleBadgeColor = computed(() => {
  switch (role.value) {
    case 'ADMIN':
      return 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30';
    case 'AUDITOR':
      return 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30';
    case 'ANALYST':
      return 'bg-amber-500/20 text-amber-400 border-amber-500/30';
    default:
      return 'bg-slate-700 text-slate-300 border-slate-600';
  }
});

const handleLogout = async () => {
  await authStore.logout();
  router.push('/login');
};
</script>

<template>
  <header class="border-b border-slate-800 bg-slate-900/90 backdrop-blur sticky top-0 z-40">
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between h-16">
      <!-- Logo & Main Nav -->
      <div class="flex items-center space-x-8">
        <router-link to="/" class="flex items-center space-x-3 group">
          <div class="w-9 h-9 rounded-lg bg-gradient-to-tr from-emerald-500 to-cyan-500 flex items-center justify-center font-bold text-slate-950 shadow-lg shadow-emerald-500/20 group-hover:scale-105 transition-transform">
            ⚡
          </div>
          <div>
            <span class="text-lg font-bold bg-gradient-to-r from-slate-100 to-slate-300 bg-clip-text text-transparent">ReconStream</span>
            <span class="hidden sm:inline-block text-xs text-slate-400 ml-2 px-1.5 py-0.5 rounded bg-slate-800 border border-slate-700">v1.0</span>
          </div>
        </router-link>

        <nav v-if="authStore.isAuthenticated" class="hidden md:flex space-x-1">
          <router-link
            to="/"
            class="px-3 py-2 rounded-md text-sm font-medium transition-colors"
            :class="route.path === '/' ? 'bg-slate-800 text-emerald-400' : 'text-slate-300 hover:text-white hover:bg-slate-800/60'"
          >
            Overview & Discrepancies
          </router-link>
          <router-link
            to="/ledger"
            class="px-3 py-2 rounded-md text-sm font-medium transition-colors"
            :class="route.path === '/ledger' ? 'bg-slate-800 text-emerald-400' : 'text-slate-300 hover:text-white hover:bg-slate-800/60'"
          >
            Double-Entry Ledger
          </router-link>
          <router-link
            to="/chaos"
            class="px-3 py-2 rounded-md text-sm font-medium transition-colors"
            :class="route.path === '/chaos' ? 'bg-slate-800 text-emerald-400' : 'text-slate-300 hover:text-white hover:bg-slate-800/60'"
          >
            Settlement & Chaos Lab
          </router-link>
        </nav>
      </div>

      <!-- User Profile / Auth State -->
      <div v-if="authStore.isAuthenticated" class="flex items-center space-x-4">
        <div class="flex items-center space-x-2">
          <span class="text-xs px-2.5 py-1 rounded-full font-semibold border" :class="roleBadgeColor">
            {{ role }}
          </span>
          <span class="text-sm text-slate-300 hidden sm:inline">{{ user?.email }}</span>
        </div>
        <button
          @click="handleLogout"
          class="text-xs font-medium text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 px-3 py-1.5 rounded-md border border-slate-700 hover:border-rose-500/30 transition-colors"
        >
          Sign Out
        </button>
      </div>
      <div v-else>
        <router-link
          to="/login"
          class="text-sm font-medium px-4 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg transition-colors shadow"
        >
          Sign In
        </router-link>
      </div>
    </div>
  </header>
</template>
