<script setup lang="ts">
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';

const authStore = useAuthStore();
const router = useRouter();
const route = useRoute();

const email = ref('');
const password = ref('');
const errorMessage = ref<string | null>(null);

const handleLogin = async (loginEmail?: string, loginPassword?: string) => {
  const e = loginEmail || email.value;
  const p = loginPassword || password.value;
  if (!e || !p) {
    errorMessage.value = 'Please enter email and password';
    return;
  }

  errorMessage.value = null;
  try {
    await authStore.login(e, p);
    const redirect = (route.query.redirect as string) || '/';
    router.push(redirect);
  } catch (err: any) {
    errorMessage.value = err.response?.data?.message || 'Invalid credentials or server unavailable';
  }
};
</script>

<template>
  <div class="min-h-[calc(100vh-4rem)] flex items-center justify-center py-12 px-4 sm:px-6 lg:px-8">
    <div class="max-w-md w-full space-y-8 bg-slate-900/90 border border-slate-800 p-8 rounded-2xl shadow-2xl backdrop-blur-sm">
      <div class="text-center space-y-2">
        <div class="w-12 h-12 rounded-xl bg-gradient-to-tr from-emerald-500 to-cyan-500 flex items-center justify-center font-bold text-2xl text-slate-950 mx-auto shadow-lg shadow-emerald-500/20">
          ⚡
        </div>
        <h2 class="text-2xl font-bold tracking-tight text-white">ReconStream Access Portal</h2>
        <p class="text-xs text-slate-400">
          Stateless HttpOnly Cookie Authentication with Role-Based Access Control
        </p>
      </div>

      <div v-if="errorMessage" class="p-3 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
        {{ errorMessage }}
      </div>

      <form class="space-y-4" @submit.prevent="handleLogin()">
        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Email Address</label>
          <input
            v-model="email"
            type="email"
            required
            placeholder="admin@reconstream.dev"
            class="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
          />
        </div>

        <div>
          <label class="block text-xs font-medium text-slate-300 mb-1">Password</label>
          <input
            v-model="password"
            type="password"
            required
            placeholder="••••••••"
            class="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-lg text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-emerald-500 transition"
          />
        </div>

        <button
          type="submit"
          :disabled="authStore.loading"
          class="w-full py-2.5 px-4 rounded-lg text-sm font-semibold text-slate-950 bg-gradient-to-r from-emerald-400 to-cyan-400 hover:from-emerald-300 hover:to-cyan-300 transition shadow-lg shadow-emerald-500/20 disabled:opacity-50 font-medium"
        >
          {{ authStore.loading ? 'Authenticating...' : 'Sign In' }}
        </button>
      </form>

      <!-- Quick Demo Role Switcher -->
      <div class="pt-6 border-t border-slate-800 space-y-3">
        <div class="text-xs font-semibold text-slate-400 uppercase tracking-wider text-center">
          One-Click Demo Profiles
        </div>
        <div class="grid grid-cols-3 gap-2">
          <button
            type="button"
            @click="handleLogin('admin@reconstream.dev', 'password123')"
            class="p-2.5 rounded-lg border border-emerald-500/30 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-300 text-center transition group"
          >
            <div class="text-xs font-bold">ADMIN</div>
            <div class="text-[10px] text-slate-400 mt-0.5">Full Access</div>
          </button>

          <button
            type="button"
            @click="handleLogin('auditor@reconstream.dev', 'password123')"
            class="p-2.5 rounded-lg border border-cyan-500/30 bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-300 text-center transition group"
          >
            <div class="text-xs font-bold">AUDITOR</div>
            <div class="text-[10px] text-slate-400 mt-0.5">CSV + AI Copilot</div>
          </button>

          <button
            type="button"
            @click="handleLogin('analyst@reconstream.dev', 'password123')"
            class="p-2.5 rounded-lg border border-amber-500/30 bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 text-center transition group"
          >
            <div class="text-xs font-bold">ANALYST</div>
            <div class="text-[10px] text-slate-400 mt-0.5">Read-Only</div>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
