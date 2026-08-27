import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import 'primeicons/primeicons.css'
import './style.css'
import App from './App.vue'

createApp(App)
  .use(PrimeVue, {
    theme: {
      preset: Aura,
      options: { darkModeSelector: false, cssLayer: false }
    },
    ripple: true,
    locale: {
      startsWith: '开头是', contains: '包含', notContains: '不包含', endsWith: '结尾是',
      equals: '等于', notEquals: '不等于', noFilter: '无筛选', filter: '筛选',
      clear: '清除', apply: '应用', matchAll: '全部匹配', matchAny: '任一匹配',
      addRule: '增加规则', removeRule: '移除规则', accept: '确定', reject: '取消',
      choose: '选择', upload: '上传', cancel: '取消', emptyMessage: '暂无内容'
    }
  })
  .mount('#app')
