import pathlib,json,copy
p=pathlib.Path('M:/BaoTin/Infra/monitoring');out=p/'import'
old=json.loads((p/'dashboards/baotin-overview.json').read_text(encoding='utf8'))
ds={'type':'prometheus','uid':'${DS_PROMETHEUS}'}
d=copy.deepcopy(old)
d.update({'uid':'baotin-operations','title':'BaoTin — Operations Overview','editable':True,'id':None,'version':1,'description':'Application readiness, public HTTPS, supporting-service reachability and monitoring collectors. TCP success is reachability only, not dependency correctness.','__inputs':[{'name':'DS_PROMETHEUS','label':'Prometheus','description':'Select the existing BaoTin Prometheus datasource','type':'datasource','pluginId':'prometheus','pluginName':'Prometheus'}],'__requires':[{'type':'grafana','id':'grafana','name':'Grafana','version':'11.0.0'},{'type':'datasource','id':'prometheus','name':'Prometheus','version':'1.0.0'}]})
d['templating']['list'][0]['datasource']=ds
d['templating']['list'][0]['query']='label_values(probe_success{job=~"service-readiness|website-https|supporting-services"}, service)'
d['templating']['list'][0]['definition']=d['templating']['list'][0]['query']
d['links']=[{'title':'BaoTin website','type':'link','url':'https://baotin.rimiprod.me/','targetBlank':True},{'title':'Jenkins','type':'link','url':'https://jenkins.rimiprod.me/','targetBlank':True},{'title':'SonarQube','type':'link','url':'https://sonarqube.rimiprod.me/','targetBlank':True}]
panels=[]
def row(title,y):
 panels.append({'id':len(panels)+1,'type':'row','title':title,'collapsed':False,'gridPos':{'x':0,'y':y,'w':24,'h':1},'panels':[]})
def panel(template,title,expr,x,y,w,h,description,unit=None,legend='{{service}}'):
 q=copy.deepcopy(old['panels'][template]);q.update({'id':len(panels)+1,'title':title,'description':description,'datasource':ds,'gridPos':{'x':x,'y':y,'w':w,'h':h}})
 q['targets']=[{'refId':'A','expr':expr,'legendFormat':legend,'instant':q['type'] in ('stat','table')}]
 if q['type']=='table':q['targets'][0]['format']='table'
 if unit:q['fieldConfig']['defaults']['unit']=unit
 q['fieldConfig']['defaults']['noValue']='NO DATA'
 panels.append(q)
sel='{job=~"service-readiness|website-https|supporting-services",service=~"$service"}'
row('1. Application and service health',0)
panel(0,'Backend and Expo readiness','probe_success{job="service-readiness",service=~"$service"}',0,1,24,5,'UP=readiness JSON status UP; DOWN=failed probe. NO DATA is not healthy.')
panel(0,'Supporting services — HTTP readiness / TCP reachability','probe_success{job="supporting-services",service=~"$service"}',0,6,24,5,'SonarQube, Jenkins, MinIO, Grafana HTTP checks; PostgreSQL/Redis/Kafka TCP reachability only. Apply bundled config to populate these series.')
row('2. Public HTTPS and certificates',11)
panel(0,'Public HTTPS login pages and BaoTin website','probe_success{job="website-https",service=~"$service"}',0,12,24,5,'HTTPS200 + HTML + valid TLS. Login-page availability is not an authenticated business transaction.')
panel(2,'TLS certificate days remaining','(probe_ssl_earliest_cert_expiry{job="website-https",service=~"$service"} - time()) / 86400',0,17,12,5,'Red below14 days, yellow below30 days; check Cloudflare certificate renewal.','d')
panel(0,'HTTP response code','probe_http_status_code'+sel,12,17,12,5,'Actual probe HTTP status. TCP-only targets do not emit this metric.','none')
panels[-1]['fieldConfig']['defaults']['mappings']=[];panels[-1]['options']['colorMode']='value';panels[-1]['fieldConfig']['defaults']['thresholds']={'mode':'absolute','steps':[{'color':'red','value':None},{'color':'green','value':200},{'color':'yellow','value':300},{'color':'red','value':400}]}
row('3. Availability and synthetic latency',22)
panel(1,'Probe availability — selected interval','100 * avg_over_time(probe_success'+sel+'[$__range])',0,23,24,5,'Availability of collected synthetic probes. Samples before monitoring started are not invented; this is not request success rate.','percent')
panel(3,'Probe duration','probe_duration_seconds'+sel,0,28,12,7,'Synthetic probe duration, not API request latency p95.','s')
panel(4,'Availability timeline','probe_success'+sel,12,28,12,7,'Correlate local service failure with public edge failure.')
row('4. Monitoring collectors and alerts',35)
panel(5,'Scrape target health','up',0,36,12,5,'Exporter/scrape health is separate from target readiness. A successful scrape can still contain probe_success=0.',legend='{{job}} {{service}}')
panel(6,'Firing alerts','ALERTS{alertstate="firing"}',12,36,12,5,'No rows is normal when no alerts fire. Rules evaluate in Prometheus; notification delivery is not configured.')
panel(7,'Collector resident memory','process_resident_memory_bytes{job=~"prometheus|blackbox"}',0,41,12,7,'Memory used by Prometheus/Blackbox only, not application JVM or whole Windows host.','bytes','{{job}}')
panel(8,'Collector CPU cores','rate(process_cpu_seconds_total{job=~"prometheus|blackbox"}[5m])',12,41,12,7,'Collector CPU core consumption; not whole host utilization.','cores','{{job}}')
d['panels']=panels
(out/'BaoTin-Operations-Grafana.json').write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('CREATED import dashboard: panels='+str(sum(x['type']!='row' for x in panels))+' rows=4 datasource=select-on-import editable=YES')
