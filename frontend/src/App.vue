<script setup lang="ts">
import Navbar from './components/Navbar.vue';
</script>

<template>
  <div class="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-emerald-500/30 selection:text-emerald-300">
    <Navbar />
    <main class="flex-1">
      <router-view />
    </main>
    <footer class="border-t border-slate-900 bg-slate-950 py-4 px-6 text-center text-xs text-slate-600">
      ReconStream — Financial Grade Double-Entry Settlement & AI Reconciliation Engine
    </footer>
  </div>
</template>
