"""Download local Chinese snapshots of official interview and topic study plans.

Only public metadata is shipped. Statements and language snippets stay in .cache.
One sequential request at a time, with a delay and a resumable per-question cache.
"""
import ast
import argparse
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".cache/content/leetcode"
PLANS = {
    "hot100": ("top-100-liked", "LeetCode Hot 100"),
    "leetcode75": ("leetcode-75", "LeetCode 75"),
    "interview150": ("top-interview-150", "面试经典 150"),
    "programming": ("programming-skills", "编程基础"),
    "dp": ("dynamic-programming", "动态规划"),
    "graph": ("graph-theory", "图论"),
    "binary": ("binary-search", "二分查找"),
}
REQUEST_DELAY = 2.0
LAST_REQUEST = 0.0
QUERY = """query questionData($titleSlug: String!) { question(titleSlug: $titleSlug) {
questionFrontendId title translatedTitle titleSlug content translatedContent difficulty
topicTags { name translatedName slug } exampleTestcases codeSnippets { lang langSlug code }
} }"""


def request(query, variables=None):
    global LAST_REQUEST
    # All callers run on one thread. Space every request, including failed ones.
    time.sleep(max(0, LAST_REQUEST + REQUEST_DELAY - time.monotonic()))
    req = urllib.request.Request("https://leetcode.cn/graphql/", data=json.dumps({"query": query, "variables": variables or {}}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0", "Referer": "https://leetcode.cn/"})
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            value = json.load(response)
    finally:
        LAST_REQUEST = time.monotonic()
    if value.get("errors"):
        raise ValueError("LeetCode query failed")
    return value["data"]


class Text(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts=[]; self.pre=False
    def handle_starttag(self, tag, attrs):
        if tag == "pre": self.parts.append("\n```text\n")
        elif tag in {"p", "div", "li", "br", "h2", "h3"}: self.parts.append("\n")
        elif tag == "code": self.parts.append("`")
        elif tag == "img":
            props=dict(attrs); self.parts.append("\n![示意图]("+props.get("src", "")+")\n")
    def handle_endtag(self, tag):
        if tag == "pre": self.parts.append("\n```\n")
        elif tag == "code": self.parts.append("`")
        elif tag in {"p", "div", "li"}: self.parts.append("\n")
    def handle_data(self, value): self.parts.append(value)


def text(raw):
    parser=Text(); parser.feed(raw); return re.sub(r"\n{3,}", "\n\n", "".join(parser.parts)).strip()


def examples(raw, parameter_names=None):
    result=[]
    blocks=re.findall(r'<pre[^>]*>(.*?)</pre>|<div class="example-block">(.*?)</div>',raw,re.S)
    for pair in blocks:
        block=pair[0] or pair[1]
        clean=html.unescape(re.sub(r"<[^>]+>", "", re.sub(r'</p>|<br\s*/?>', '\n', block))).replace('\xa0',' ')
        match=re.search(r"(?:输入|Input)\s*[:：](.*?)\n?\s*(?:输出|Output)\s*[:：]\s*([^\n]+)",clean,re.S)
        if not match: continue
        inp,out=match.groups(); values={}
        pairs=list(re.finditer(r"(\w+)\s*=\s*",inp))
        try:
            for i,pair in enumerate(pairs):
                value=inp[pair.end():pairs[i+1].start() if i+1<len(pairs) else len(inp)].strip().rstrip(",，").strip()
                try: values[pair.group(1)]=json.loads(value)
                except ValueError: values[pair.group(1)]=ast.literal_eval(value)
            if not pairs and parameter_names:
                value=inp.strip()
                try: values[parameter_names[0]]=json.loads(value)
                except ValueError: values[parameter_names[0]]=ast.literal_eval(value)
            elif pairs and inp[:pairs[0].start()].strip().rstrip(',').strip() and parameter_names:
                prefix=inp[:pairs[0].start()].strip().rstrip(',').strip()
                values={parameter_names[0]:json.loads(prefix),**values}
            expected=json.JSONDecoder().raw_decode(out.strip())[0]
            if parameter_names and len(values)==len(parameter_names): values=dict(zip(parameter_names,values.values()))
            if values: result.append({"input":values,"expected":expected,"explanation":"LeetCode 公开样例"})
        except (ValueError, TypeError, SyntaxError): pass
    return result


PY_SUPPORT = '''
class ListNode:
    def __init__(self, val=0, next=None): self.val, self.next = val, next
class TreeNode:
    def __init__(self, val=0, left=None, right=None): self.val, self.left, self.right = val, left, right
def linked(values):
    head = ListNode(); cur = head
    for v in values or []: cur.next = ListNode(v); cur = cur.next
    return head.next
def tree(values):
    if not values or values[0] is None: return None
    nodes=[None if v is None else TreeNode(v) for v in values]; child=1
    for node in nodes:
        if node:
            if child<len(nodes): node.left=nodes[child]; child+=1
            if child<len(nodes): node.right=nodes[child]; child+=1
    return nodes[0]
def encode(value):
    if isinstance(value,ListNode):
        result=[]
        while value: result.append(value.val); value=value.next
        return result
    if isinstance(value,TreeNode):
        result=[]; queue=[value]
        for node in queue:
            result.append(None if node is None else node.val)
            if node: queue.extend([node.left,node.right])
        while result and result[-1] is None: result.pop()
        return result
    if isinstance(value,list): return [encode(v) for v in value]
    return value
'''
JS_SUPPORT = '''
function ListNode(val=0,next=null) { this.val=val; this.next=next; }
function TreeNode(val=0,left=null,right=null) { this.val=val; this.left=left; this.right=right; }
function linked(values) { let head=new ListNode(),cur=head; for(const v of values||[]){cur.next=new ListNode(v);cur=cur.next;} return head.next; }
function tree(values) { if(!values?.length||values[0]===null)return null;const nodes=values.map(v=>v===null?null:new TreeNode(v));let i=1;for(const node of nodes)if(node){if(i<nodes.length)node.left=nodes[i++];if(i<nodes.length)node.right=nodes[i++];}return nodes[0]; }
function encode(value) { if(value instanceof ListNode){const a=[];while(value){a.push(value.val);value=value.next;}return a;}if(value instanceof TreeNode){const a=[],q=[value];for(let i=0;i<q.length;i++){const n=q[i];a.push(n?n.val:null);if(n)q.push(n.left,n.right);}while(a.length&&a.at(-1)===null)a.pop();return a;}if(Array.isArray(value))return value.map(encode);return value; }
'''


def bridges(question, snippets, cases):
    """Produce independently editable stdio programs around official function signatures."""
    templates=json.loads((ROOT/"data/seed/algorithm-templates.json").read_text(encoding="utf-8"))
    supported=[]
    if not cases: return templates,supported
    keys=list(cases[0]["input"])
    py=snippets.get("python3", "")
    sig=re.search(r"def\s+(\w+)\(self,\s*(.*?)\)\s*->",py)
    if not sig: return templates,supported
    method,parameters=sig.groups()
    return_type=re.search(r'->\s*([^:]+):', py[sig.start():])
    if method == '__init__' or not re.search(r'class\s+Solution\b', py): return templates,supported
    if return_type and re.search(r'(?<![A-Za-z])Node\b', return_type.group(1)): return templates,supported
    params=re.findall(r"(\w+)\s*:\s*(.*?)(?=,\s*\w+\s*:|$)",parameters)
    if not params or any(name not in keys for name,_ in params) or any("Node" in typ and "ListNode" not in typ and "TreeNode" not in typ for _,typ in params): return templates,supported
    def conversion(name, typ, language):
        expression=(f'input[{json.dumps(name)}]' if language in {"python","javascript"} else f'input.get("{name}")')
        if 'List[' in typ and 'ListNode' in typ:
            return f'[linked(value) for value in {expression}]' if language=='python' else f'{expression}.map(linked)'
        if "ListNode" in typ: return f'linked({expression})'
        if "TreeNode" in typ: return f'tree({expression})'
        return expression
    callargs=','.join(conversion(name,typ,"python") for name,typ in params)
    mutated=bool(re.search(r"->\s*None",py))
    templates["python"]='from typing import *\nfrom collections import *\nimport json, sys, math, heapq, bisect\n'+PY_SUPPORT+'\n'+py+'\n\ninput=json.load(sys.stdin)\nargs=['+callargs+']\nresult=Solution().'+method+'(*args)\n'+('result=args[0]\n' if mutated else '')+'print(json.dumps(encode(result),ensure_ascii=False))\n'
    supported.append("python")
    js=snippets.get("javascript", "")
    js_match=re.search(r"(?:var|const|let)\s+(\w+)\s*=\s*function\s*\((.*?)\)",js)
    if js_match:
        js_method=js_match.group(1); args=','.join(conversion(name,typ,"javascript") for name,typ in params)
        templates["javascript"]='const fs=require("node:fs");\n'+JS_SUPPORT+'\n'+js+'\nconst input=JSON.parse(fs.readFileSync(0,"utf8"));\nconst args=['+args+'];\nlet result='+js_method+'(...args);\n'+('result=args[0];\n' if mutated else '')+'console.log(JSON.stringify(encode(result)));\n'
        supported.append("javascript")
    j=snippets.get("java", "")
    jm=re.search(r"public\s+([\w<>, ?\[\]]+)\s+(\w+)\s*\(([^)]*)\)",j)
    simple=True; converted=[]
    if jm:
        for parameter in jm.group(3).split(','):
            match=re.fullmatch(r"\s*(.*?)\s+(\w+)\s*",parameter)
            if not match: simple=False;break
            typ,name=match.groups(); v=f'input.get("{name}")'
            if name not in keys: simple=False;break
            if typ=='int': v=f'((Number){v}).intValue()'
            elif typ=='double': v=f'((Number){v}).doubleValue()'
            elif typ=='String': v=f'(String){v}'
            elif typ=='int[]': v=f'numbers({v})'
            elif typ=='int[][]': v=f'matrix({v})'
            elif typ=='char[]': v=f'chars({v})'
            elif typ=='char[][]': v=f'charMatrix({v})'
            elif typ=='String[]': v=f'((List<?>){v}).toArray(new String[0])'
            elif typ=='ListNode': v=f'linked({v})'
            elif typ=='TreeNode': v=f'tree({v})'
            else: simple=False;break
            converted.append((typ,name,v))
    else: simple=False
    if simple:
        declarations='\n'.join(f'        {typ} {name}={v};' for typ,name,v in converted)
        invoke=f'new Solution().{jm.group(2)}('+','.join(name for _,name,_ in converted)+')'
        solution=('        '+invoke+';\n        return encode('+converted[0][1]+');') if jm.group(1).strip()=='void' else ('        return encode('+invoke+');')
        base=templates['java']; base=re.sub(r'        // 示例：.*?return null; // TODO',declarations+'\n'+solution,base,flags=re.S)
        support='''
    static int[][] matrix(Object o) { return ((List<?>)o).stream().map(Main::numbers).toArray(int[][]::new); }
    static char[] chars(Object o) { if(o instanceof String s)return s.toCharArray(); return ((List<?>)o).stream().map(x->x.toString()).collect(java.util.stream.Collectors.joining()).toCharArray(); }
    static char[][] charMatrix(Object o) { return ((List<?>)o).stream().map(Main::chars).toArray(char[][]::new); }
    static ListNode linked(Object o) { ListNode h=new ListNode(),c=h;for(int v:numbers(o)){c.next=new ListNode(v);c=c.next;}return h.next; }
    static TreeNode tree(Object o) { List<?> a=(List<?>)o;if(a.isEmpty()||a.get(0)==null)return null;List<TreeNode> nodes=new ArrayList<>();for(Object v:a)nodes.add(v==null?null:new TreeNode(((Number)v).intValue()));int i=1;for(TreeNode n:nodes)if(n!=null){if(i<nodes.size())n.left=nodes.get(i++);if(i<nodes.size())n.right=nodes.get(i++);}return nodes.get(0); }
    static Object encode(Object o) { if(o instanceof ListNode n){List<Integer>a=new ArrayList<>();while(n!=null){a.add(n.val);n=n.next;}return a;}if(o instanceof TreeNode n){List<Integer>a=new ArrayList<>();List<TreeNode>q=new ArrayList<>();q.add(n);for(int i=0;i<q.size();i++){TreeNode t=q.get(i);a.add(t==null?null:t.val);if(t!=null){q.add(t.left);q.add(t.right);}}while(!a.isEmpty()&&a.get(a.size()-1)==null)a.remove(a.size()-1);return a;}return o; }
'''
        base=base.replace('    // 输入输出工具；',support+'\n    // 输入输出工具；')
        definitions='\nclass ListNode { int val; ListNode next; ListNode(){} ListNode(int v){val=v;} ListNode(int v,ListNode n){val=v;next=n;} }\nclass TreeNode { int val; TreeNode left,right; TreeNode(){} TreeNode(int v){val=v;} TreeNode(int v,TreeNode l,TreeNode r){val=v;left=l;right=r;} }\n'
        templates['java']=base+'\n'+definitions+j+'\n';supported.append('java')
    g=snippets.get('golang','');gm=re.search(r'func\s+(\w+)\s*\((.*?)\)\s*([^\{]*)\{',g,re.S)
    simple=True; declarations=[];gnames=[]
    if gm:
        for parameter in gm.group(2).split(','):
            match=re.fullmatch(r'\s*(\w+)\s+(.+?)\s*',parameter)
            if not match:simple=False;break
            name,typ=match.groups()
            if name not in keys:simple=False;break
            if typ=='int': conv=f'int(input["{name}"].(float64))'
            elif typ=='string':conv=f'input["{name}"].(string)'
            elif typ=='[]int':conv=f'numbers(input["{name}"])'
            elif typ=='[][]int':conv=f'matrix(input["{name}"])'
            elif typ=='[]string':conv=f'strings(input["{name}"])'
            elif typ=='*ListNode':conv=f'linked(input["{name}"])'
            elif typ=='*TreeNode':conv=f'tree(input["{name}"])'
            else:simple=False;break
            declarations.append(f'    {name} := {conv}');gnames.append(name)
    else:simple=False
    if simple:
        invocation=gm.group(1)+'('+','.join(gnames)+')'
        body='\n'.join(declarations)+'\n'+('    '+invocation+'\n    return encode('+gnames[0]+')' if not gm.group(3).strip() else '    return encode('+invocation+')')
        base=templates['go'];base=re.sub(r'    // JSON 数字.*?return nil // TODO',body,base,flags=re.S)
        support='''
type ListNode struct { Val int; Next *ListNode }
type TreeNode struct { Val int; Left,Right *TreeNode }
func matrix(o interface{}) [][]int { a:=o.([]interface{});r:=make([][]int,len(a));for i,v:=range a{r[i]=numbers(v)};return r }
func strings(o interface{}) []string { a:=o.([]interface{});r:=make([]string,len(a));for i,v:=range a{r[i]=v.(string)};return r }
func linked(o interface{}) *ListNode {h:=&ListNode{};c:=h;for _,v:=range numbers(o){c.Next=&ListNode{Val:v};c=c.Next};return h.Next}
func tree(o interface{}) *TreeNode {a:=o.([]interface{});if len(a)==0||a[0]==nil{return nil};nodes:=make([]*TreeNode,len(a));for i,v:=range a{if v!=nil{nodes[i]=&TreeNode{Val:int(v.(float64))}}};j:=1;for _,n:=range nodes{if n!=nil{if j<len(nodes){n.Left=nodes[j];j++};if j<len(nodes){n.Right=nodes[j];j++}}};return nodes[0]}
func encode(o interface{}) interface{} {switch n:=o.(type){case *ListNode:r:=[]int{};for n!=nil{r=append(r,n.Val);n=n.Next};return r;case *TreeNode:r:=[]interface{}{};q:=[]*TreeNode{n};for i:=0;i<len(q);i++{t:=q[i];if t==nil{r=append(r,nil)}else{r=append(r,t.Val);q=append(q,t.Left,t.Right)}};for len(r)>0&&r[len(r)-1]==nil{r=r[:len(r)-1]};return r};return o}
'''
        templates['go']=base+'\n'+support+'\n'+g+'\n';supported.append('go')
    return templates,supported


def plan_members(plan):
    return {q["titleSlug"] for g in plan["planSubGroups"] for q in g["questions"]}


def select_questions(all_questions, memberships):
    selected = []
    for question in all_questions:
        number = question["questionFrontendId"]
        if not number.isdigit():
            continue
        sets = [key for key, slugs in memberships.items() if question["titleSlug"] in slugs]
        if 1 <= int(number) <= 100:
            sets.append("first100")
        if sets:
            selected.append((question, sets))
    return sorted(selected, key=lambda item: int(item[0]["questionFrontendId"]))


def cached_json(path, fetch, offline=False):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    if offline:
        raise ValueError(f"缺少本地缓存：{path.name}。请先联网导入。")
    value = fetch()
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)
    return value


def main():
    global REQUEST_DELAY
    parser = argparse.ArgumentParser(description="逐题串行导入官方中文题单；题面只保存本地。")
    parser.add_argument("--offline", action="store_true", help="仅使用已有缓存，不发送网络请求")
    parser.add_argument("--delay", type=float, default=2.0, help="网络请求间隔秒数，最少 1 秒")
    args = parser.parse_args()
    REQUEST_DELAY = max(1.0, args.delay)
    CACHE.mkdir(parents=True,exist_ok=True)
    allq = cached_json(CACHE/'all.json', lambda: {'data':request('{allQuestions{questionFrontendId title titleSlug translatedTitle difficulty topicTags{name translatedName slug}}}')}, args.offline)['data']['allQuestions']
    memberships={}
    for key, (slug, label) in PLANS.items():
        plan_path = CACHE/('hot.json' if key == 'hot100' else 'plan-'+slug+'.json')
        plan = cached_json(plan_path, lambda slug=slug: {'data':request('query($slug:String!){studyPlanV2Detail(planSlug:$slug){name planSubGroups{name questions{titleSlug}}}}', {'slug':slug})}, args.offline)['data'].get('studyPlanV2Detail')
        if not plan or not plan_members(plan):
            raise ValueError(f'官方题单暂不可用：{label}；已有题库保留。')
        memberships[key]=plan_members(plan)
        print(f'{label}: {len(memberships[key])}', flush=True)
    selected=select_questions(allq, memberships)
    selected_slugs={q['titleSlug'] for q,_ in selected}
    missing=set().union(*memberships.values())-selected_slugs
    if missing:
        raise ValueError(f'题目索引缺少 {len(missing)} 道题，请更新 all.json 缓存。')
    index=[];records=[]
    for i,(meta,sets) in enumerate(selected):
        slug=meta['titleSlug'];path=CACHE/(slug+'.json')
        question=cached_json(path, lambda slug=slug: request(QUERY,{'titleSlug':slug})['question'], args.offline)
        row={'id':'lc-'+meta['questionFrontendId'].zfill(3)+'-'+slug,'number':int(meta['questionFrontendId']),'title':meta['translatedTitle'] or meta['title'],'slug':slug,'difficulty':{'Easy':'简单','Medium':'中等','Hard':'困难'}[meta['difficulty']],'tag':(meta.get('topicTags') or [{'translatedName':'综合'}])[0].get('translatedName') or '综合','collections':sets,'source_url':'https://leetcode.cn/problems/'+slug+'/','source':'LeetCode 官方原题（本地快照）'}
        index.append(row)
        raw=question.get('translatedContent') or ''
        snippets={s['langSlug']:s['code'] for s in question.get('codeSnippets') or []}
        signature=re.search(r'def\s+\w+\(self,\s*(.*?)\)\s*->',snippets.get('python3',''))
        names=re.findall(r'(\w+)\s*:',signature.group(1)) if signature else []
        samples=examples(raw,names)
        templates,supported=bridges(question,snippets,samples)
        if int(meta['questionFrontendId']) in {26,27,80,141,142,160,236,138}: supported=[]
        if int(meta['questionFrontendId'])==95: supported=[lang for lang in supported if lang in {'python','javascript'}]
        notes={language:('修改 Solution 中的函数，输入输出与链表/二叉树转换由模板处理。' if language in supported else '本题可在这里编辑官方函数模板；复杂接口请到 LeetCode 原题运行和提交。') for language in ['java','go','python','javascript']}
        for language,slugname in [('java','java'),('go','golang'),('python','python3'),('javascript','javascript')]:
            if language not in supported and slugname in snippets: templates[language]=snippets[slugname]
        visible={language:snippets.get(slugname,templates[language]) for language,slugname in [('java','java'),('go','golang'),('python','python3'),('javascript','javascript')]}
        records.append({**row,'description':text(raw) or '官方中文题面暂不可用，请通过原题链接查看。','examples':samples,'tests':[],'hints':[],'editorial':'本题为 LeetCode 原题，请通过原题链接查看官方题解与社区讨论。','templates':visible,'runner_templates':templates,'solution_markers':visible,'language_notes':notes,'runnable_languages':supported,'judge_scope':'examples','official':True})
        print(f'LeetCode {i+1}/{len(selected)} #{row["number"]} languages={",".join(supported)}',flush=True)
    collections=[{'id':key,'title':label,'source_url':'https://leetcode.cn/studyplan/'+slug+'/'} for key,(slug,label) in PLANS.items()]+[{'id':'first100','title':'题号 1–100','source_url':'https://leetcode.cn/problemset/'}]
    summary={key:sum(key in r['collections'] for r in records) for key in [*PLANS,'first100']}
    for collection in collections:
        collection['count']=summary[collection['id']]
    (ROOT/'data/seed/leetcode-index.json').write_text(json.dumps({'collections':collections,'problems':index},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (ROOT/'.cache/content/leetcode.learning.json').write_text(json.dumps({'collections':collections,'problems':records},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'total':len(records),**summary,'runnable':sum(bool(r['runnable_languages']) for r in records)},ensure_ascii=False))


if __name__=='__main__': main()
