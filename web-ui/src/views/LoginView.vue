<script lang="ts" setup>
  import { reactive, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { Message } from '@arco-design/web-vue';
  import { IconUser, IconLock } from '@arco-design/web-vue/es/icon';
  import { useAuthStore } from '@/stores/auth';
  import { buildDynamicRoutes } from '@/router';
  import { wsClient } from '@/utils/ws';
  import LogoMark from '@/components/LogoMark.vue';

  const auth = useAuthStore();
  const route = useRoute();
  const router = useRouter();
  const form = reactive({ username: '', password: '' });
  const loading = ref(false);
  const errorMessage = ref('');

  async function submit() {
    if (!form.username || !form.password) {
      errorMessage.value = '请输入用户名和密码';
      return;
    }
    loading.value = true;
    errorMessage.value = '';
    try {
      await auth.login(form.username, form.password);
      if (auth.mustChangePwd) {
        // 强制改密期间后端拒绝 WS 握手, 改密重登后再连接
        router.replace({ name: 'change-password' });
        return;
      }
      wsClient.connect();
      // 跳转前先注册动态路由, 避免 vue-router 首次解析动态路径时的 R0004 告警
      buildDynamicRoutes(auth.menus);
      router.replace((route.query.redirect as string) || '/');
    } catch (e) {
      // 保留后端原始提示(如 锁定/停用), 401 包络不带 toast, 此处兜底展示
      errorMessage.value = (e as Error)?.message || '登录失败';
    } finally {
      loading.value = false;
    }
  }
</script>

<template>
  <div class="login-container">
    <!-- 左侧品牌区: 深蓝科技底 + 网格 + 光晕 -->
    <div class="login-banner">
      <div class="grid-bg"></div>
      <div class="glow glow-1"></div>
      <div class="glow glow-2"></div>
      <div class="brand">
        <div class="brand-logo">
          <LogoMark :size="72" />
        </div>
        <h1 class="brand-name">煜见视觉</h1>
        <p class="brand-sub">YuJianVision · GB28181 视频管理平台</p>
        <div class="features">
          <div class="feature-item">
            <span class="dot"></span>国标设备接入 · 自动目录同步
          </div>
          <div class="feature-item">
            <span class="dot"></span>实时预览 · 宫格分屏 · 秒开起播
          </div>
          <div class="feature-item">
            <span class="dot"></span>云台控制 · 预置位 · 远程管理
          </div>
        </div>
      </div>
    </div>

    <!-- 右侧表单区 -->
    <div class="login-form-area">
      <div class="form-box">
        <div class="form-logo"><LogoMark :size="44" /></div>
        <h2 class="form-title">欢迎登录</h2>
        <p class="form-desc">视频监控管理平台</p>
        <a-form :model="form" layout="vertical" size="large" @submit-success="submit">
          <a-form-item hide-asterisk feedback>
            <a-input v-model="form.username" placeholder="用户名" allow-clear>
              <template #prefix><icon-user /></template>
            </a-input>
          </a-form-item>
          <a-form-item hide-asterisk feedback>
            <a-input-password v-model="form.password" placeholder="密码" allow-clear>
              <template #prefix><icon-lock /></template>
            </a-input-password>
          </a-form-item>
          <div v-if="errorMessage" class="error-msg">{{ errorMessage }}</div>
          <a-button type="primary" html-type="submit" long size="large" :loading="loading">
            登 录
          </a-button>
        </a-form>
        <p class="copyright">© 2026 YuJianVision</p>
      </div>
    </div>
  </div>
</template>

<style scoped lang="less">
  .login-container {
    display: flex;
    height: 100vh;
    width: 100vw;
    overflow: hidden;
  }

  .login-banner {
    position: relative;
    flex: 1.2;
    display: flex;
    align-items: center;
    justify-content: center;
    background: linear-gradient(135deg, #071a3a 0%, #0e2a66 45%, #165dff 130%);
    overflow: hidden;

    .grid-bg {
      position: absolute;
      inset: 0;
      background-image:
        linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
        linear-gradient(90deg, rgba(255, 255, 255, 0.06) 1px, transparent 1px);
      background-size: 44px 44px;
      mask-image: radial-gradient(ellipse at center, #000 30%, transparent 75%);
    }

    .glow {
      position: absolute;
      border-radius: 50%;
      filter: blur(90px);
      opacity: 0.45;
    }
    .glow-1 {
      width: 420px;
      height: 420px;
      top: -120px;
      right: -80px;
      background: #14c9c9;
    }
    .glow-2 {
      width: 380px;
      height: 380px;
      bottom: -140px;
      left: -60px;
      background: #165dff;
    }

    .brand {
      position: relative;
      z-index: 1;
      color: #fff;
      text-align: center;
      .brand-logo {
        display: flex;
        justify-content: center;
        margin-bottom: 18px;
        filter: drop-shadow(0 0 18px rgba(20, 201, 201, 0.55));
      }
      .brand-name {
        margin: 0;
        font-size: 40px;
        letter-spacing: 14px;
        text-indent: 14px;
        font-weight: 600;
      }
      .brand-sub {
        margin: 10px 0 0;
        font-size: 15px;
        letter-spacing: 2px;
        color: rgba(255, 255, 255, 0.72);
      }
      .features {
        margin-top: 42px;
        display: flex;
        flex-direction: column;
        gap: 14px;
        align-items: flex-start;
        .feature-item {
          display: flex;
          align-items: center;
          gap: 10px;
          font-size: 14px;
          color: rgba(255, 255, 255, 0.85);
          .dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: #14c9c9;
            box-shadow: 0 0 8px #14c9c9;
          }
        }
      }
    }
  }

  .login-form-area {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-bg-1);

    .form-box {
      width: 360px;
      text-align: center;
      .form-logo {
        display: flex;
        justify-content: center;
        margin-bottom: 14px;
      }
      .form-title {
        margin: 0;
        font-size: 26px;
      }
      .form-desc {
        margin: 8px 0 34px;
        color: var(--color-text-3);
        font-size: 13px;
        letter-spacing: 1px;
      }
      .error-msg {
        margin: -6px 0 12px;
        color: rgb(var(--danger-6));
        font-size: 13px;
        text-align: left;
      }
      .copyright {
        margin-top: 40px;
        color: var(--color-text-4);
        font-size: 12px;
      }
    }
  }

  /* 窄屏隐藏品牌区 */
  @media (max-width: 900px) {
    .login-banner {
      display: none;
    }
  }
</style>
