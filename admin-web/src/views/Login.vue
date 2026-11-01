<script setup lang="ts">
import { ref, reactive, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { post, saveSession, staffRoles, errorText, type User } from '../lib/api'
import { loginDestination } from '../router'
const route = useRoute(), router = useRouter(), formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' }), loading = ref(false), error = ref('')
let alive = true
const rules: FormRules = {
  username: [{ required: true, whitespace: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
onBeforeUnmount(() => { alive = false })
async function submit() {
  if (loading.value) return
  error.value = ''; loading.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !alive) return
    const result = await post<{token: string; user: User}>('/auth/admin/login', { username: form.username.trim(), password: form.password })
    if (!alive) return
    if (!staffRoles.includes(result.user.role)) throw new Error('此账号没有后台访问权限')
    saveSession(result.token, result.user)
    await router.replace(loginDestination(route.query.redirect))
  } catch (err) { if (alive) { error.value = errorText(err); await nextTick(); document.querySelector<HTMLElement>('.login-error')?.focus() } }
  finally { if (alive) loading.value = false }
}
</script>
<template>
  <main class="login-page">
    <header class="login-brand"><AppIcon name="Van" :size="24"/><strong>预约出行</strong><span>运营管理后台</span></header>
    <section class="login-card">
      <h1>管理员登录</h1><p>使用已授权的运营、财务或管理员账号登录。</p>
      <div v-if="error" class="login-error" tabindex="-1" role="alert"><el-alert :title="error" type="error" show-icon :closable="false"/></div>
      <el-form ref="formRef" :model="form" :rules="rules" :disabled="loading" label-position="top" @submit.prevent="submit">
        <el-form-item label="账号" prop="username"><el-input v-model="form.username" size="large" placeholder="请输入管理员账号" autocomplete="username" :maxlength="64" clearable/></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" size="large" type="password" show-password placeholder="请输入密码" autocomplete="current-password" :maxlength="128"/></el-form-item>
        <el-button class="login-submit" type="primary" size="large" native-type="submit" :loading="loading">{{ loading ? '正在登录…' : '登录' }}</el-button>
      </el-form>
      <p class="login-note">账号由平台管理员开通。如忘记密码，请联系平台管理员重置。</p>
    </section>
  </main>
</template>
