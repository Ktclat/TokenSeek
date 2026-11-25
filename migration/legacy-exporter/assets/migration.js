'use strict'

const trigger = document.getElementById('start')
const statusText = document.getElementById('status')
const bridge = window.LegacyTransfer
const validProfile = id => id === 'default' || /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id)
const request = operation => new Promise((resolve, reject) => {
  operation.onsuccess = () => resolve(operation.result)
  operation.onerror = () => reject(operation.error)
})

function existingDatabase(name) {
  return new Promise((resolve, reject) => {
    let absent = false
    const operation = indexedDB.open(name)
    operation.onupgradeneeded = event => {
      // An absent workspace has no history. Abort creation of an empty database.
      absent = event.oldVersion === 0
      operation.transaction.abort()
    }
    operation.onerror = () => absent ? resolve(null) : reject(operation.error)
    operation.onsuccess = () => resolve(operation.result)
  })
}
function read(db, store, key) {
  return request(db.transaction(store, 'readonly').objectStore(store).get(key))
}
function check(ok) { if (!ok) throw Error('无法写入迁移文件，请检查设备可用空间后重试。') }
async function record(owner, kind, id, value) {
  const blob = value instanceof Blob ? value : new Blob([JSON.stringify(value)])
  check(bridge.begin(owner, kind, id, String(blob.size)))
  for (let i = 0; i < blob.size; i += 32768) {
    const slice = blob.slice(i, i+32768)
    const encoded = await new Promise((resolve,reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result.split(',')[1])
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(slice)
    })
    check(bridge.append(encoded))
  }
  check(bridge.endRecord())
}
async function migrate() {
  trigger.disabled = true
  try {
    check(bridge.start())
    const rawDirectory = localStorage.getItem('tokenseek:profiles:v1')
    const directory = rawDirectory ? JSON.parse(rawDirectory) : {active:'default',profiles:[{id:'default',username:'TokenSeek',nickname:'TokenSeek'}]}
    if (!Array.isArray(directory.profiles) || !directory.profiles.some(p=>p.id===directory.active)) throw Error('旧版账户目录无法读取。')
    const profiles = directory.profiles.map(({id,username,nickname,avatar}) => {
      if (!validProfile(id) || typeof username !== 'string' || typeof nickname !== 'string') throw Error('账户资料无法读取。')
      return {id,username,nickname,...(typeof avatar==='string'?{avatar}:{})}
    })
    await record('','directory','',{active:directory.active,profiles})
    const settings = {global:{}}
    for (const [name, key] of Object.entries({theme:'chat-demo:theme-preference',font:'tokenseek:font-size',locale:'chat-demo:locale',voice:'chat-demo:tts-voice',location:'chat-demo:location'})) {
      const val=localStorage.getItem(key)
      if(val!==null) settings.global[name]=val
    }
    let count=0
    for(const profile of profiles) {
      const owner=profile.id, suffix=owner==='default'?'':':'+owner
      statusText.textContent='正在复制 '+profile.nickname+'…'
      const personalization=localStorage.getItem('tokenseek:answer-preferences'+suffix)
      if(personalization){
        const {style,language}=JSON.parse(personalization)
        settings[owner]={style,language}
      }
      const db=await existingDatabase('chat-demo'+suffix)
      if(!db) continue
      try {
        const sessionIds=new Set(), files=new Set()
        for(const [store,kind] of [['sessions','session'],['files','file']]){
          if(!db.objectStoreNames.contains(store))continue
          const keys=await request(db.transaction(store,'readonly').objectStore(store).getAllKeys())
          for(const key of keys){
            const row=await read(db,store,key)
            if(!row || typeof row.id!=='string' || row.id!==key)throw Error('旧版记录不完整。')
            if(kind==='file' && !(row.blob instanceof Blob))throw Error('附件原件无法读取。')
            await record(owner,kind,row.id,kind==='file'?row.blob:row)
            if(kind==='session'){sessionIds.add(row.id);count++}else files.add(row.id)
          }
        }
        if(db.objectStoreNames.contains('organization')){
          const org=await read(db,'organization','current')
          if(org) await record(owner,'organization','',org)
        }
        if(db.objectStoreNames.contains('resume')){
          const resume=await read(db,'resume','current')
          if(resume){
            const drafts=[]
            for(const [sessionId,original] of (resume.drafts?.drafts ?? [])){
              if(sessionId!==null && !sessionIds.has(sessionId))continue
              const attachments=[]
              for(const a of original.attachments ?? []){
                const source=a.file
                if(!(source instanceof Blob))throw Error('草稿附件无法读取。')
                const upload=resume.drafts?.uploads?.[a.id]
                attachments.push({id:a.id,name:source.name,size:source.size,mimeType:source.type,isImage:Boolean(a.isImage),serverId:upload?.status==='done'?upload.serverId:null})
                if(!files.has(a.id)){await record(owner,'file',a.id,source);files.add(a.id)}
              }
              drafts.push({sessionId,text:original.text,attachments})
            }
            await record(owner,'resume','',{activeId:sessionIds.has(resume.activeId)?resume.activeId:null,drafts})
          }
        }
      } finally {db.close()}
    }
    await record('','settings','',settings)
    check(bridge.publish())
    statusText.textContent='已准备 '+profiles.length+' 个账户、'+count+' 个对话。现在可直接覆盖安装原生版，勿卸载本应用。'
  } catch(error) {
    bridge.cancel()
    trigger.disabled=false
    statusText.textContent='迁移未完成。'+(error?.message || '请重试。')
  }
}
trigger.addEventListener('click',migrate)
