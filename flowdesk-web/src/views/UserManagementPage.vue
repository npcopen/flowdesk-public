<script setup lang="ts">
import '@/management.css'
import { computed, onMounted, ref } from 'vue'
import { iamApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { mutationIntent } from '@/lib/mutationIntent'
import { allPages } from '@/lib/allPages'
import { roleLabels, permissionDisplayLabel } from '@/lib/format'
import type { UserSummary, Role, Permission, AccessBinding } from '@/types/api'
import ErrorNotice from '@/components/ErrorNotice.vue'
import ResourceState from '@/components/ResourceState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
const auth=useAuthStore()
const roleName=(role:Role)=>role.roleName===role.roleCode?roleLabels[role.roleCode]:role.roleName
const users=ref<UserSummary[]>([]), roles=ref<Role[]>([]), permissions=ref<Permission[]>([])
const loading=ref(true), saving=ref(false), error=ref<unknown>(null), actionError=ref<unknown>(null)
const search=ref(''), selected=ref<UserSummary|null>(null), bindings=ref<AccessBinding[]>([])
const selectedRole=ref<Role|null>(null), permissionBindings=ref<AccessBinding[]>([])
const showCreate=ref(false), form=ref({ username:'', displayName:'', initialPassword:'', status:'ACTIVE' as const })
const intent=mutationIntent(), bindingIntent=mutationIntent()
const selectablePermissions=computed(()=>permissions.value.filter(p=>!selectedRole.value||!p.allowedRoles||p.allowedRoles.includes(selectedRole.value.roleCode)))
const visibleUsers=computed(()=>users.value.filter(u=>`${u.displayName} ${u.username}`.toLowerCase().includes(search.value.toLowerCase())))
async function load() {
 loading.value=true; error.value=null
 try {
  const result=await Promise.all([
   allPages(t=>iamApi.users({pageSize:100,pageToken:t,sort:'displayName'})),
   auth.can('IAM_ROLE_READ')?allPages(t=>iamApi.roles({pageSize:100,pageToken:t,sort:'roleCode'})):Promise.resolve([]),
   auth.can('IAM_PERMISSION_READ')?allPages(t=>iamApi.permissions({pageSize:100,pageToken:t,sort:'permissionCode'})):Promise.resolve([]),
  ])
  users.value=result[0]; roles.value=result[1]; permissions.value=result[2]
 } catch(e) {error.value=e} finally {loading.value=false}
}
async function create() {
 saving.value=true;actionError.value=null
 try { await iamApi.createUser(form.value,intent.key(form.value)); intent.reset(); showCreate.value=false;form.value={username:'',displayName:'',initialPassword:'',status:'ACTIVE'};await load() }
 catch(e){actionError.value=e}finally{saving.value=false}
}
async function status(user:UserSummary) {
 saving.value=true;actionError.value=null
 try {await iamApi.patchUser(user.userId,{status:user.status==='ACTIVE'?'INACTIVE':'ACTIVE'},`"lv:${user.lockVersion}"`);await load()}
 catch(e){actionError.value=e}finally{saving.value=false}
}
async function editRoles(user:UserSummary) {
 selected.value=user;bindings.value=[];saving.value=true;actionError.value=null
 try {bindings.value=await allPages(t=>iamApi.userRoles(user.userId,{pageSize:100,pageToken:t,sort:'roleCode'}))}
 catch(e){actionError.value=e;selected.value=null}finally{saving.value=false}
}
async function editPermissions(role:Role) {
 selectedRole.value=role;permissionBindings.value=[];saving.value=true;actionError.value=null
 try {permissionBindings.value=await allPages(t=>iamApi.rolePermissions(role.roleId,{pageSize:100,pageToken:t,sort:'permissionCode'}))}
 catch(e){actionError.value=e;selectedRole.value=null}finally{saving.value=false}
}
function isGranted(list:AccessBinding[],id:string){return list.some(b=>b.targetId===id&&b.state==='GRANTED')}
async function toggle(targetId:string,kind:'role'|'permission') {
 const subject=kind==='role'?selected.value:selectedRole.value
 if(!subject)return
 const subjectId=kind==='role'?selected.value!.userId:selectedRole.value!.roleId
 const list=kind==='role'?bindings.value:permissionBindings.value
 const prior=list.find(b=>b.targetId===targetId)
 const body={state:(prior?.state==='GRANTED'?'REVOKED':'GRANTED') as 'GRANTED'|'REVOKED'}
 const options={create:!prior,etag:prior?`"lv:${prior.lockVersion}"`:undefined,key:bindingIntent.key({kind,subjectId,targetId,...body})}
 saving.value=true;actionError.value=null
 try {
  const result=kind==='role'?await iamApi.putUserRole(subjectId,targetId,body,options):await iamApi.putRolePermission(subjectId,targetId,body,options)
  const next=[...list.filter(b=>b.targetId!==targetId),result.data]
  if(kind==='role')bindings.value=next;else permissionBindings.value=next
  bindingIntent.reset();await load()
 }catch(e){actionError.value=e}finally{saving.value=false}
}
onMounted(load)
</script>
<template>
 <div class="page-heading"><div><p class="eyebrow">组织设置</p><h1>用户与权限</h1><p class="lede">管理成员账号、角色及角色拥有的操作权限。</p></div><button v-if="auth.can('IAM_USER_MANAGE')" class="button" @click="showCreate=true">新增用户</button></div>
 <ErrorNotice v-if="error" :error="error" @retry="load"/><ErrorNotice v-if="actionError" :error="actionError" :retryable="false"/>
 <section class="panel management-panel"><div class="panel-header"><h2>组织成员</h2><label>搜索成员 <input v-model="search" placeholder="姓名或账号" /></label></div>
 <p class="panel-body muted">离职或不再使用的账号可停用，历史工单和审批记录会保留。新登录立即使用最新设置，已有会话需重新登录或等待凭证到期。</p>
 <ResourceState :loading="loading" :empty="!visibleUsers.length" empty-text="暂无成员"><div class="table-wrap"><table class="data-table management-table"><thead><tr><th>姓名 / 账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody>
 <tr v-for="user in visibleUsers" :key="user.userId"><td><strong>{{user.displayName}}</strong><br/><span class="muted">{{user.username}}</span></td><td data-label="角色">{{roles.filter(r=>user.roleCodes.includes(r.roleCode)).map(r=>roleName(r)).join('、')||'尚未分配'}}</td><td data-label="状态"><StatusBadge :value="user.status==='ACTIVE'?'ACTIVE_STATUS':user.status"/></td><td><div class="row-actions"><button v-if="auth.can('IAM_BINDING_MANAGE')&&auth.can('IAM_BINDING_READ')" class="button button--secondary button--small" :disabled="saving" @click="editRoles(user)">分配角色</button><button v-if="auth.can('IAM_USER_MANAGE')" class="button button--ghost button--small" :disabled="saving||user.userId===auth.user?.userId" @click="status(user)">{{user.status==='ACTIVE'?'停用账号':'恢复账号'}}</button></div></td></tr>
 </tbody></table></div></ResourceState></section>
 <section v-if="auth.can('IAM_PERMISSION_READ')&&auth.can('IAM_BINDING_READ')" class="panel role-panel management-panel"><div class="panel-header"><div><h2>角色权限</h2><p>修改角色的权限会影响所有拥有该角色的成员。组织管理员的基础管理权限固定保留。</p></div></div><div class="panel-body role-actions"><button v-for="role in roles" :key="role.roleId" class="button button--secondary" :disabled="saving" @click="editPermissions(role)">{{roleName(role)}}</button></div></section>
 <div v-if="showCreate" class="management-modal"><section class="management-dialog panel" role="dialog" aria-modal="true" aria-labelledby="create-user-title"><div class="panel-header"><h2 id="create-user-title">新增用户</h2></div><form class="panel-body" @submit.prevent="create"><div class="form-grid"><label class="form-field">账号<input v-model.trim="form.username" required maxlength="128" autocomplete="off"/></label><label class="form-field">姓名<input v-model.trim="form.displayName" required maxlength="128"/></label><label class="form-field form-field--full">初始密码<input v-model="form.initialPassword" type="password" required maxlength="256" autocomplete="new-password"/></label></div><p class="muted">创建后请为该成员分配角色，才能办理相应业务。</p><ErrorNotice v-if="actionError" :error="actionError" :retryable="false"/><div class="form-actions"><button type="button" class="button button--secondary" :disabled="saving" @click="showCreate=false">取消</button><button class="button" :disabled="saving">创建用户</button></div></form></section></div>
 <div v-if="selected||selectedRole" class="management-modal"><section class="management-dialog panel" role="dialog" aria-modal="true" aria-labelledby="access-title"><div class="panel-header"><h2 id="access-title">{{selected?`${selected.displayName}的角色`:`${selectedRole?roleName(selectedRole):''}的权限`}}</h2><button class="button button--ghost" :disabled="saving" @click="selected=null;selectedRole=null">完成</button></div><div class="panel-body access-list"><ErrorNotice v-if="actionError" :error="actionError" :retryable="false"/><template v-if="selected"><label v-for="role in roles" :key="role.roleId"><input type="checkbox" :checked="isGranted(bindings,role.roleId)" :disabled="saving||role.status!=='ACTIVE'||(selected.userId===auth.user?.userId&&role.roleCode==='TENANT_ADMIN')" @change="toggle(role.roleId,'role')"/>{{roleName(role)}} <span class="muted">{{role.status==='ACTIVE'?'':'（已停用）'}}</span></label></template><template v-else><label v-for="permission in selectablePermissions" :key="permission.permissionId"><input type="checkbox" :aria-label="permissionDisplayLabel(permission.permissionCode,permission.permissionName)" :checked="isGranted(permissionBindings,permission.permissionId)" :disabled="saving||!auth.can('IAM_BINDING_MANAGE')||(selectedRole?.roleCode==='TENANT_ADMIN'&&permission.permissionCode.startsWith('IAM_'))" @change="toggle(permission.permissionId,'permission')"/><span>{{permissionDisplayLabel(permission.permissionCode,permission.permissionName)}}<small>{{permission.description}}</small></span></label></template></div></section></div>
</template>
<style scoped>
.role-panel{margin-top:20px}.role-actions,.row-actions{display:flex;flex-wrap:wrap;gap:8px}.access-list{max-height:65vh;overflow:auto;display:grid;gap:14px}.access-list label{display:flex;align-items:start;gap:10px}.access-list small{display:block;color:var(--muted);margin-top:4px}.panel-header label{font-size:13px}.modal-panel{max-height:90vh;overflow:auto}
</style>
