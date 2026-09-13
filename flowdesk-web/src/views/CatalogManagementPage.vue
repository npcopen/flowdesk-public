<script setup lang="ts">
import '@/management.css'
import { computed, onMounted, ref } from 'vue'
import { catalogApi, workflowApi, type CatalogWrite } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { allPages } from '@/lib/allPages'
import { ApiClientError } from '@/api/errors'
import { mutationIntent } from '@/lib/mutationIntent'
import type { ServiceCatalogItem, SlaPolicySummary, WorkflowVersion, WorkflowDefinition } from '@/types/api'
import ErrorNotice from '@/components/ErrorNotice.vue'
import ResourceState from '@/components/ResourceState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
const auth=useAuthStore(), intent=mutationIntent()
const items=ref<ServiceCatalogItem[]>([]), policies=ref<SlaPolicySummary[]>([]), versions=ref<WorkflowVersion[]>([]), definitions=ref<WorkflowDefinition[]>([])
const loading=ref(true), saving=ref(false), opened=ref(false), editing=ref<ServiceCatalogItem|null>(null)
const error=ref<unknown>(null), actionError=ref<unknown>(null), ruleError=ref<unknown>(null), search=ref(''), versionId=ref('')
const empty=():CatalogWrite=>({itemCode:'',itemName:'',description:'',riskLevel:'NORMAL',slaPolicyId:'',workflowRef:null,status:'ACTIVE'})
const form=ref<CatalogWrite>(empty())
const filtered=computed(()=>items.value.filter(i=>`${i.itemName} ${i.itemCode}`.toLowerCase().includes(search.value.toLowerCase())))
const highVersions=computed(()=>versions.value.filter(v=>v.status==='PUBLISHED'&&v.condition.value==='HIGH'))
const versionName=(id:string)=>{const v=versions.value.find(v=>v.workflowVersionId===id);return v?`${definitions.value.find(d=>d.definitionId===v.definitionId)?.definitionName||'审批规则'} · 第 ${v.versionNo} 版`:'原有审批版本'}
async function load(){
 loading.value=true;error.value=null;ruleError.value=null
 try{[items.value,policies.value]=await Promise.all([allPages(t=>catalogApi.items({pageSize:100,pageToken:t,sort:'itemName'})),allPages(t=>catalogApi.slaPolicies({pageSize:100,pageToken:t,sort:'policyName'}))])}
 catch(e){error.value=e}finally{loading.value=false}
 if(auth.can('WORKFLOW_READ_TENANT'))try{
  definitions.value=await allPages(t=>workflowApi.definitions({pageSize:100,pageToken:t,sort:'definitionName'}))
  versions.value=(await Promise.all(definitions.value.map(d=>allPages(t=>workflowApi.versions(d.definitionId,{pageSize:100,pageToken:t,sort:'-versionNo'}))))).flat()
 }catch(e){ruleError.value=e}
}
function edit(item:ServiceCatalogItem|null){
 editing.value=item;actionError.value=null;opened.value=true;intent.reset()
 form.value=item?{itemCode:item.itemCode,itemName:item.itemName,description:item.description||'',riskLevel:item.riskLevel as 'NORMAL'|'HIGH',slaPolicyId:item.slaPolicyId,workflowRef:item.workflowRef,status:item.status as 'ACTIVE'|'INACTIVE'}:empty()
 if(!form.value.slaPolicyId)form.value.slaPolicyId=policies.value.find(p=>p.status==='ACTIVE')?.slaPolicyId||''
 versionId.value=item?.workflowRef?.workflowVersionId||''
}
async function save(){
 const v=versions.value.find(v=>v.workflowVersionId===versionId.value)
 if(form.value.riskLevel==='HIGH'&&!v){actionError.value=new ApiClientError({status:422,code:'WORKFLOW_VERSION_REQUIRED'});return}
 const body:CatalogWrite={...form.value,workflowRef:form.value.riskLevel==='HIGH'&&v?{definitionId:v.definitionId,workflowVersionId:v.workflowVersionId,versionNo:v.versionNo}:null}
 saving.value=true;actionError.value=null
 try{if(editing.value)await catalogApi.configure(editing.value.catalogItemId,body,`"lv:${editing.value.lockVersion}"`);else await catalogApi.create(body,intent.key(body));intent.reset();opened.value=false;await load()}
 catch(e){actionError.value=e}finally{saving.value=false}
}
onMounted(load)
</script>
<template>
 <div class="page-heading"><div><p class="eyebrow">服务设置</p><h1>工单类型</h1><p class="lede">配置员工可以申请的服务、处理时效和审批规则。</p></div><button v-if="auth.can('CATALOG_UPDATE_MINIMAL')" class="button" :disabled="loading" @click="edit(null)">新增类型</button></div>
 <ErrorNotice v-if="error" :error="error" @retry="load"/><ErrorNotice v-if="ruleError" :error="ruleError" @retry="load"/>
 <section class="panel management-panel"><div class="panel-header"><h2>服务目录</h2><input v-model="search" aria-label="搜索工单类型" placeholder="名称或编码"/></div><div class="panel-body muted">审批规则发布后，在这里选择新版本并保存即可用于新申请。停用类型后，已有工单仍可继续处理。</div><ResourceState :loading="loading" :empty="!filtered.length" empty-text="暂无工单类型"><div class="table-wrap"><table class="data-table management-table"><thead><tr><th>类型</th><th>风险</th><th>生效审批规则</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filtered" :key="item.catalogItemId"><td><strong>{{item.itemName}}</strong><br/><span class="muted">{{item.itemCode}}</span></td><td data-label="风险">{{item.riskLevel==='HIGH'?'高风险，需审批':'普通'}}</td><td data-label="生效审批规则">{{item.workflowRef?versionName(item.workflowRef.workflowVersionId):'无需审批'}}</td><td data-label="状态"><StatusBadge :value="item.status==='ACTIVE'?'ACTIVE_STATUS':item.status"/></td><td><button v-if="auth.can('CATALOG_UPDATE_MINIMAL')" class="button button--secondary button--small" @click="edit(item)">配置类型</button></td></tr></tbody></table></div></ResourceState></section>
 <div v-if="opened" class="management-modal"><section class="management-dialog panel" role="dialog" aria-modal="true" aria-labelledby="catalog-title"><div class="panel-header"><h2 id="catalog-title">{{editing?'配置工单类型':'新增工单类型'}}</h2></div><form class="panel-body" @submit.prevent="save"><ErrorNotice v-if="actionError" :error="actionError" :retryable="false"/><div class="form-grid"><label class="form-field">类型编码<input v-model.trim="form.itemCode" pattern="[A-Z][A-Z0-9_]{0,63}" maxlength="64" required :disabled="!!editing" placeholder="例如 LAPTOP_REPAIR"/><small>使用大写字母、数字和下划线，创建后保持不变。</small></label><label class="form-field">类型名称<input v-model.trim="form.itemName" required maxlength="128"/></label><label class="form-field form-field--full">服务说明<textarea v-model="form.description" maxlength="1000"/></label><label class="form-field">风险级别<select v-model="form.riskLevel"><option value="NORMAL">普通，无需审批</option><option value="HIGH">高风险，需要审批</option></select></label><label class="form-field">处理时效<select v-model="form.slaPolicyId" required><option value="" disabled>请选择策略</option><option v-for="policy in policies" :key="policy.slaPolicyId" :value="policy.slaPolicyId" :disabled="policy.status!=='ACTIVE'">{{policy.policyName}}</option></select></label><label v-if="form.riskLevel==='HIGH'" class="form-field form-field--full">生效审批版本<select v-model="versionId" required><option value="" disabled>请选择已发布版本</option><option v-for="version in highVersions" :key="version.workflowVersionId" :value="version.workflowVersionId">{{versionName(version.workflowVersionId)}}</option></select><small>新申请使用所选版本，正在办理的申请保留原审批记录。</small></label><label class="form-field">状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label></div><div class="form-actions"><button type="button" class="button button--secondary" :disabled="saving" @click="opened=false">取消</button><button class="button" :disabled="saving||!form.slaPolicyId">保存类型</button></div></form></section></div>
</template>
<style scoped>.modal-panel{max-height:90vh;overflow:auto}.form-field small{color:var(--muted);font-size:12px;line-height:1.6}</style>
