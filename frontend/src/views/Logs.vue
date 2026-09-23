<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import {
  ElTable, ElTableColumn, ElSelect, ElOption, ElButton, ElMessage, ElTag,
  ElPagination, ElEmpty
} from 'element-plus'
import type { AdaptLogSnapshot, FlightRoute } from '../api'
import { adaptApi, routeApi } from '../api'

/** 从服务端导出的快照 Blob 里读 total（解析失败按 null 处理，不影响下载本身）。 */
const readExportTotal = async (blob: Blob): Promise<number | null> => {
  try {
    const parsed = JSON.parse(await blob.text()) as { total?: number }
    return typeof parsed.total === 'number' ? parsed.total : null
  } catch {
    return null
  }
}

/* 页面展示的始终是“最后一次已确认筛选”的快照 */
const snapshot = ref<AdaptLogSnapshot | null>(null)
const routes = ref<FlightRoute[]>([])
const selectedRouteId = ref<number>(0)

const loading = ref(false)
const exporting = ref(false)
const loadError = ref('')

const PAGE_SIZE = 20

/**
 * 读取序号：每次发起查询都 +1，响应返回时只有“仍为最新一次”的请求允许写页面。
 * 先选航线甲再立刻改选乙、或甲慢返回时，甲的旧响应会被直接丢弃，不会覆盖乙。
 * 不依赖按钮禁用——直接调接口/连续触发同样安全。
 */
let loadSeq = 0
const loadLogs = async (page = 1) => {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const routeId = selectedRouteId.value || undefined
    const result = await adaptApi.logsSnapshot(routeId, page, PAGE_SIZE)
    if (seq !== loadSeq) return // 已有更新的筛选/翻页/刷新请求，旧响应丢弃
    snapshot.value = result
  } catch (e) {
    if (seq !== loadSeq) return // 旧请求失败也不能覆盖新结果或清掉新结果的状态
    loadError.value = e instanceof Error ? e.message : '查询失败'
    ElMessage.error(`流水查询失败：${loadError.value}`)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

const loadRoutes = async () => {
  try {
    routes.value = await routeApi.list()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '航线列表加载失败')
  }
}

/* 切换航线：回到第 1 页并立即发新查询，旧航线在途响应由 loadSeq 挡掉 */
const handleFilter = () => {
  loadLogs(1)
}

const handlePageChange = (page: number) => {
  loadLogs(page)
}

const handleRefresh = () => {
  loadLogs(snapshot.value?.page ?? 1)
}

/**
 * 导出：把“当前确认的筛选”与当前页一起交给服务端生成快照，
 * 前端不拿页面数组拼 JSON。连续点击导出时 abort 掉前一个在途请求，
 * 只有最后一次确认的筛选对应的响应会落盘。
 */
let exportController: AbortController | null = null
const handleExport = async () => {
  exportController?.abort()
  const controller = new AbortController()
  exportController = controller

  const routeId = selectedRouteId.value || undefined
  exporting.value = true
  try {
    const { blob, filename } = await adaptApi.exportLogs(routeId, controller.signal)
    if (controller.signal.aborted) return // 已被更新的一次导出取代
    // 以“本次下载的快照”自身的 total 判定空命中，而不是可能已过期的页面快照
    const exportedTotal = await readExportTotal(blob)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(exportedTotal === 0
      ? '已导出：当前筛选没有命中记录（快照 total=0）'
      : `导出成功，共 ${exportedTotal ?? ''} 条`)
  } catch (e) {
    // axios v1 取消旧请求抛 CanceledError（被更新的一次导出取代），不当失败提示
    if (e instanceof DOMException && e.name === 'AbortError') return
    if ((e as { code?: string }).code === 'ERR_CANCELED') return
    ElMessage.error(`导出失败：${e instanceof Error ? e.message : '请求失败'}`)
  } finally {
    if (exportController === controller) {
      exporting.value = false
      exportController = null
    }
  }
}

onMounted(() => {
  loadLogs(1)
  loadRoutes()
})

onBeforeUnmount(() => {
  exportController?.abort()
})

const getOperationTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    BIND: '绑定',
    UNBIND: '解绑',
    REBIND: '重新绑定',
    REJECT: '拒绝',
    OCCUPY_CONFLICT: '占用冲突'
  }
  return map[type] || type
}

const getOperationTypeColor = (type: string): 'success' | 'danger' | 'warning' | 'info' => {
  const map: Record<string, 'success' | 'danger' | 'warning' | 'info'> = {
    BIND: 'success',
    UNBIND: 'danger',
    REBIND: 'warning',
    REJECT: 'info',
    OCCUPY_CONFLICT: 'info'
  }
  return map[type] || 'info'
}

const filterLabel = () => {
  if (!selectedRouteId.value) return '全部航线'
  const route = routes.value.find(r => r.id === selectedRouteId.value)
  return route ? `${route.routeCode} - ${route.routeName}` : `航线#${selectedRouteId.value}`
}
</script>

<template>
  <div class="bg-white rounded-lg shadow p-6">
    <div class="flex justify-between items-center mb-6">
      <h2 class="text-xl font-semibold">适配调整流水记录</h2>
      <div class="flex gap-2">
        <ElButton @click="handleRefresh" :loading="loading">刷新</ElButton>
        <ElButton type="primary" :loading="exporting" @click="handleExport">导出记录</ElButton>
      </div>
    </div>

    <div class="bg-gray-50 rounded-lg p-4 mb-4">
      <div class="flex items-center gap-4">
        <span class="text-gray-600">筛选航线:</span>
        <ElSelect v-model="selectedRouteId" placeholder="全部航线" style="width: 300px;" @change="handleFilter">
          <ElOption label="全部航线" :value="0" />
          <ElOption v-for="route in routes" :key="route.id" :label="`${route.routeCode} - ${route.routeName}`" :value="route.id" />
        </ElSelect>
        <span v-if="snapshot" class="text-sm text-gray-400">
          快照时刻 {{ snapshot.queryTime }} · 共 {{ snapshot.total }} 条 · 排序 {{ snapshot.sort }}
        </span>
      </div>
    </div>

    <!-- 请求失败：明确报错并保留上一份快照，不清空、不伪装成“无数据” -->
    <div v-if="loadError" class="mb-4 px-4 py-3 rounded bg-red-50 text-red-600 text-sm">
      请求失败：{{ loadError }}（页面仍显示上一次成功加载的快照，请重试）
    </div>

    <ElTable :data="snapshot?.records ?? []" stripe v-loading="loading">
      <ElTableColumn prop="id" label="ID" width="80" />
      <ElTableColumn prop="routeCode" label="航线编号" />
      <ElTableColumn prop="anchorCode" label="锚点编号" />
      <ElTableColumn prop="operationType" label="操作类型">
        <template #default="scope">
          <ElTag :type="getOperationTypeColor(scope.row.operationType)">
            {{ getOperationTypeLabel(scope.row.operationType) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="气流强度变化(m/s)">
        <template #default="scope">
          <span v-if="scope.row.beforeWindSpeed !== null">
            {{ scope.row.beforeWindSpeed }} → {{ scope.row.afterWindSpeed }}
          </span>
          <span v-else-if="scope.row.afterWindSpeed !== null">— → {{ scope.row.afterWindSpeed }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="锚点承重(kg)">
        <template #default="scope">
          <span v-if="scope.row.afterWeight !== null">{{ scope.row.afterWeight }}</span>
          <span v-else class="text-gray-400">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="reason" label="操作原因" min-width="200" />
      <ElTableColumn prop="operator" label="操作人" />
      <ElTableColumn prop="createTime" label="操作时间" width="170" />
      <template #empty>
        <ElEmpty description="当前筛选没有命中流水记录（total = 0）" />
      </template>
    </ElTable>

    <div class="flex justify-end mt-4">
      <ElPagination
        background
        layout="total, prev, pager, next, jumper"
        :total="snapshot?.total ?? 0"
        :current-page="snapshot?.page ?? 1"
        :page-size="PAGE_SIZE"
        @current-change="handlePageChange"
      />
    </div>

    <div class="mt-3 text-xs text-gray-400">
      当前筛选：{{ filterLabel() }}；读取与导出均由服务端按同一排序（发生时间倒序 + 自增编号倒序）生成快照。
    </div>
  </div>
</template>
