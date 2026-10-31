<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Message, Modal } from '@arco-design/web-vue';
  import type { FormInstance } from '@arco-design/web-vue';
  import { userApi, roleApi } from '@/api/system';
  import type { SystemUser, RoleOption } from '@/api/system';
  import { useAuthStore } from '@/stores/auth';
  import { fmtDateTime, isValidPassword } from '@/utils/format';

  const auth = useAuthStore();
  const rows = ref<SystemUser[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const rolesAll = ref<RoleOption[]>([]);
  const query = reactive({ keyword: '', status: -1, page: 1, count: 10 });

  const drawerVisible = ref(false);
  const editing = ref<SystemUser | null>(null);
  const saving = ref(false);
  const formRef = ref<FormInstance>();
  const form = reactive({
    username: '',
    password: '',
    nickname: '',
    status: 1,
    roleIds: [] as number[]
  });

  const resetVisible = ref(false);
  const resetTarget = ref<SystemUser | null>(null);
  const resetPwd = ref('');
  const resetSaving = ref(false);

  const pagination = computed(() => ({
    total: total.value,
    current: query.page,
    pageSize: query.count,
    showTotal: true,
    showPageSize: true,
    pageSizeOptions: [10, 20, 50]
  }));

  const roleName = (ids: number[]) =>
    ids.map((id) => rolesAll.value.find((r) => r.id === id)?.roleName).filter(Boolean).join('、');

  const roleOptions = computed(() =>
    rolesAll.value.map((r) => ({ label: r.roleName, value: r.id }))
  );

  /** 当前登录用户自己: 不显示删除/不可自停用(后端也有守卫, 这里前置隐藏) */
  const isSelf = (row: SystemUser) => auth.user?.id === row.id;
  const editingSelf = computed(() => !!editing.value && isSelf(editing.value));

  const rules = computed(() => ({
    username: [{ required: true, message: '请输入用户名' }],
    password: editing.value
      ? []
      : [
          { required: true, message: '请输入初始密码' },
          {
            validator: (_v: string, cb: (msg?: string) => void) =>
              cb(isValidPassword(form.password) ? undefined : '至少 8 位且同时包含字母和数字')
          }
        ]
  }));

  async function load() {
    loading.value = true;
    try {
      const data = await userApi.page({
        keyword: query.keyword || undefined,
        status: query.status === -1 ? undefined : query.status,
        page: query.page,
        count: query.count
      });
      rows.value = data.list;
      total.value = data.total;
    } finally {
      loading.value = false;
    }
  }

  function search() {
    query.page = 1;
    void load();
  }

  function onPage(p: number) {
    query.page = p;
    void load();
  }

  function onPageSize(s: number) {
    query.count = s;
    query.page = 1;
    void load();
  }

  function openCreate() {
    editing.value = null;
    form.username = '';
    form.password = '';
    form.nickname = '';
    form.status = 1;
    form.roleIds = [];
    drawerVisible.value = true;
  }

  function openEdit(row: SystemUser) {
    editing.value = row;
    form.username = row.username;
    form.password = '';
    form.nickname = row.nickname;
    form.status = row.status;
    form.roleIds = [...row.roleIds];
    drawerVisible.value = true;
  }

  async function save() {
    const err = await formRef.value?.validate();
    if (err) return;
    saving.value = true;
    try {
      if (editing.value) {
        await userApi.update(editing.value.id, {
          nickname: form.nickname,
          status: form.status,
          roleIds: form.roleIds
        });
        Message.success('用户已更新');
      } else {
        await userApi.create({
          username: form.username,
          password: form.password,
          nickname: form.nickname,
          roleIds: form.roleIds
        });
        Message.success('用户已创建（首次登录需改密）');
      }
      drawerVisible.value = false;
      await load();
    } finally {
      saving.value = false;
    }
  }

  function openReset(row: SystemUser) {
    resetTarget.value = row;
    resetPwd.value = '';
    resetVisible.value = true;
  }

  async function doReset() {
    if (!resetTarget.value) return;
    if (!isValidPassword(resetPwd.value)) {
      Message.error('至少 8 位且同时包含字母和数字');
      return;
    }
    resetSaving.value = true;
    try {
      await userApi.resetPwd(resetTarget.value.id, resetPwd.value);
      Message.success('密码已重置（该用户将被全量下线并要求改密）');
      resetVisible.value = false;
      await load();
    } finally {
      resetSaving.value = false;
    }
  }

  function confirmRemove(row: SystemUser) {
    Modal.confirm({
      title: '删除用户',
      content: `确认删除用户「${row.username}」？其在线会话将被吊销。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { status: 'danger' },
      onOk: async () => {
        await userApi.remove(row.id);
        Message.success('删除成功');
        await load();
      }
    });
  }

  onMounted(async () => {
    void load();
    rolesAll.value = await roleApi.all();
  });
</script>

<template>
  <div class="general-card-wrap">
    <a-card :bordered="false" class="general-card">
      <div class="toolbar">
        <a-space wrap>
          <a-input-search
            v-model="query.keyword"
            placeholder="用户名 / 昵称"
            allow-clear
            style="width: 220px"
            @search="search"
            @clear="search"
          />
          <a-radio-group v-model="query.status" type="button" size="small" @change="search">
            <a-radio :value="-1">全部</a-radio>
            <a-radio :value="1">启用</a-radio>
            <a-radio :value="0">停用</a-radio>
          </a-radio-group>
        </a-space>
        <a-button v-if="auth.hasPerm('sys:user:add')" type="primary" size="small" @click="openCreate">
          <template #icon><icon-plus /></template>
          新增用户
        </a-button>
      </div>

      <a-table
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 900 }"
        row-key="id"
        size="medium"
        @page-change="onPage"
        @page-size-change="onPageSize"
      >
        <template #columns>
          <a-table-column title="用户名" :width="140">
            <template #cell="{ record }">{{ record.username }}</template>
          </a-table-column>
          <a-table-column title="昵称" :width="140">
            <template #cell="{ record }">{{ record.nickname || '-' }}</template>
          </a-table-column>
          <a-table-column title="角色" :width="160">
            <template #cell="{ record }">{{ roleName(record.roleIds) || '-' }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag size="small" :color="record.status === 1 ? 'green' : 'gray'">
                {{ record.status === 1 ? '启用' : '停用' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="待改密" :width="90" align="center">
            <template #cell="{ record }">
              <a-tag v-if="record.mustChangePwd" size="small" color="orange">是</a-tag>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="最后登录" :width="160">
            <template #cell="{ record }">{{ fmtDateTime(record.lastLogin) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="200" fixed="right">
            <template #cell="{ record }">
              <a-space :size="2">
                <a-button
                  v-if="auth.hasPerm('sys:user:update')"
                  type="text"
                  size="mini"
                  @click="openEdit(record)"
                >
                  编辑
                </a-button>
                <a-button
                  v-if="auth.hasPerm('sys:user:reset-pwd')"
                  type="text"
                  size="mini"
                  @click="openReset(record)"
                >
                  重置密码
                </a-button>
                <a-button
                  v-if="auth.hasPerm('sys:user:delete') && !isSelf(record)"
                  type="text"
                  size="mini"
                  status="danger"
                  @click="confirmRemove(record)"
                >
                  删除
                </a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <a-drawer
      :visible="drawerVisible"
      :width="560"
      unmount-on-close
      :ok-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="save"
      @cancel="drawerVisible = false"
      @close="drawerVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-user-group />
          {{ editing ? '编辑用户' : '新增用户' }}
        </span>
      </template>

      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="用户名" field="username">
          <a-input v-model="form.username" :disabled="!!editing" placeholder="登录账号" allow-clear />
        </a-form-item>
        <a-form-item v-if="!editing" label="初始密码" field="password" help="≥8 位且同时包含字母和数字；首次登录将强制改密">
          <a-input-password v-model="form.password" placeholder="初始密码" allow-clear />
        </a-form-item>
        <a-form-item label="昵称">
          <a-input v-model="form.nickname" placeholder="显示名称" allow-clear />
        </a-form-item>
        <a-form-item label="角色">
          <a-select
            v-model="form.roleIds"
            multiple
            :options="roleOptions"
            placeholder="选择角色（可多选）"
            allow-clear
          />
        </a-form-item>
        <a-form-item v-if="editing && !editingSelf" label="状态" help="停用后该用户在线会话即时吊销">
          <a-radio-group v-model="form.status" type="button">
            <a-radio :value="1">启用</a-radio>
            <a-radio :value="0">停用</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-else-if="editing && editingSelf" label="状态">
          <a-tag size="small" color="gray">当前登录账号（不可自停用）</a-tag>
        </a-form-item>
      </a-form>
    </a-drawer>

    <a-drawer
      :visible="resetVisible"
      :width="560"
      unmount-on-close
      :ok-loading="resetSaving"
      ok-text="重置"
      cancel-text="取消"
      @ok="doReset"
      @cancel="resetVisible = false"
      @close="resetVisible = false"
    >
      <template #title>
        <span class="dlg-title">
          <icon-lock />
          重置密码 · {{ resetTarget?.username }}
        </span>
      </template>
      <a-form :model="{ password: resetPwd }" layout="vertical">
        <a-form-item required label="新密码" help="≥8 位且同时包含字母和数字；重置后该用户全量下线，首次登录强制改密">
          <a-input-password v-model="resetPwd" placeholder="新密码" allow-clear />
        </a-form-item>
      </a-form>
    </a-drawer>
  </div>
</template>

<style scoped lang="less">
  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }
</style>
