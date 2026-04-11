import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import api from '../api/client';
import type { User, Role } from '../types';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null);
  const loading = ref(false);
  const initialized = ref(false);

  const isAuthenticated = computed(() => !!user.value);
  const role = computed<Role | null>(() => user.value?.role || null);
  const isAdmin = computed(() => user.value?.role === 'ADMIN');
  const isAuditor = computed(() => user.value?.role === 'AUDITOR' || user.value?.role === 'ADMIN');
  const isAnalyst = computed(() => user.value?.role === 'ANALYST');

  const fetchCurrentUser = async (): Promise<boolean> => {
    loading.value = true;
    try {
      const res = await api.get<User>('/api/v1/auth/me');
      user.value = res.data;
      return true;
    } catch {
      user.value = null;
      return false;
    } finally {
      loading.value = false;
      initialized.value = true;
    }
  };

  const login = async (email: string, password: string): Promise<User> => {
    loading.value = true;
    try {
      const res = await api.post<User>('/api/v1/auth/login', { email, password });
      user.value = res.data;
      return res.data;
    } finally {
      loading.value = false;
    }
  };

  const logout = async (): Promise<void> => {
    try {
      await api.post('/api/v1/auth/logout');
    } catch (e) {
      console.warn('Logout failed', e);
    } finally {
      user.value = null;
    }
  };

  return {
    user,
    loading,
    initialized,
    isAuthenticated,
    role,
    isAdmin,
    isAuditor,
    isAnalyst,
    fetchCurrentUser,
    login,
    logout,
  };
});
