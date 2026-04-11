import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import OverviewView from '../views/OverviewView.vue';
import LedgerView from '../views/LedgerView.vue';
import ChaosView from '../views/ChaosView.vue';
import LoginView from '../views/LoginView.vue';

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true },
  },
  {
    path: '/',
    name: 'overview',
    component: OverviewView,
    meta: { requiresAuth: true },
  },
  {
    path: '/ledger',
    name: 'ledger',
    component: LedgerView,
    meta: { requiresAuth: true },
  },
  {
    path: '/chaos',
    name: 'chaos',
    component: ChaosView,
    meta: { requiresAuth: true },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore();

  if (!authStore.initialized) {
    await authStore.fetchCurrentUser();
  }

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return next({ name: 'login', query: { redirect: to.fullPath } });
  }

  if (to.name === 'login' && authStore.isAuthenticated) {
    return next({ name: 'overview' });
  }

  next();
});

export default router;
