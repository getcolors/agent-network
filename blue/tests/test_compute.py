import json
import pytest
import yaml
from conftest import fixture
from package_agent_network_blue import compute, tools

@pytest.mark.parametrize('managed', [True, False])
async def test_singleton_artifacts_and_owned_ssh_block(tmp_path, managed):
    opts = {**fixture(), 'workdir': str(tmp_path/'render'), 'blue/event':'build'}
    if not managed: opts['vultr-ssh-keys'] = ['existing-key']
    result = await compute.compute_step(opts)
    assert result['blue/exit'] == 0 and result['name'] == opts['profile']
    assert result['ip'] == '192.0.2.10' and result['vpc_ip'] is None
    await tools.ansible_local_step(result)
    play = yaml.safe_load((tmp_path/'render/agent-network-fixture/agent-network-ansible-local/main.yml').read_text())
    assert play[0]['vars']['colors_keygen'] is managed
    code = play[0]['tasks'][0]['ansible.builtin.command']['argv'][-1]
    scope = {'__name__':'copied_updater'}
    exec(compile(code, '<packaged-ssh-updater>', 'exec'), scope)
    home = tmp_path/'home'; home.mkdir()
    payload = {'host_alias': opts['profile'], 'ssh_hosts':[{'name':opts['profile'],'ip':result['ip'],'user':result['user']}], 'keygen':managed, 'block_state':'present'}
    (home/'.ssh').mkdir()
    (home/'.ssh/config').write_text(f'# BEGIN {opts["profile"]} ANSIBLE MANAGED BLOCK\nHost {opts["profile"]}\n HostName 198.51.100.4\n# END {opts["profile"]} ANSIBLE MANAGED BLOCK\n')
    assert scope['update'](payload, home)
    config = (home/'.ssh/config').read_text()
    assert 'Host agent-network-fixture\n' in config and 'HostName 192.0.2.10' in config
    assert ('IdentityFile ~/.ssh/agent-network-fixture' in config) is managed
    assert not scope['update'](payload, home)
    assert scope['update']({**payload,'block_state':'absent'}, home)
    (home/'.ssh/config').write_text('Host=agent-network-fixture\n HostName 198.51.100.8\n')
    with pytest.raises(ValueError): scope['update'](payload,home)
    assert '198.51.100.8' in (home/'.ssh/config').read_text()
    docs=json.loads((tmp_path/'render/agent-network-fixture/compute/nodes/0/node-none.tf.json').read_text())
    assert 'provisioner' not in json.dumps(docs)

async def test_compute_refusal_stops_before_app_mutation(tmp_path, monkeypatch):
    async def refuse(*args):return {'status':'error','errors':['owned state mismatch']}
    monkeypatch.setattr(compute,'orchestrate',refuse)
    result=await compute.compute_step({**fixture(),'blue/event':'create','workdir':str(tmp_path)})
    assert result['blue/exit']==1 and result['blue/err']=='owned state mismatch'
    assert 'ip' not in result

async def test_external_private_path_is_forwarded_to_ansible(tmp_path, monkeypatch):
    captured={}
    async def fake(opts,specs,**options):captured.update(options);return opts
    monkeypatch.setattr(tools,'ansible_with_spec',fake)
    await tools.ansible_step({**fixture(),'workdir':str(tmp_path),'ip':'192.0.2.10','user':'ubuntu','ssh-private-key-path':'/selected/key'})
    assert captured['private_key']=='/selected/key'


def test_preflight_recognizes_equals_host():
    from package_agent_network_blue.ssh_config import foreign_stanza_line
    assert foreign_stanza_line(["Host *", "Host=agent-network-fixture"], "agent-network-fixture") == 2


async def test_stun_and_ipv6_policy_are_preserved(tmp_path):
    opts = {**fixture(), 'workdir':str(tmp_path), 'blue/event':'build'}
    result = await compute.compute_step(opts)
    document = json.loads((tmp_path/'agent-network-fixture/compute/nodes/0/node-none.tf.json').read_text())
    assert document['resource']['vultr_instance']['node']['enable_ipv6'] is False
    rules = compute.requirements(opts)['security']['ingress']
    assert [r['from_port'] for r in rules if r['protocol']=='tcp'] == [22,80,443]
    assert [r['from_port'] for r in rules if r['protocol']=='udp'] == [opts['agent-network-stun-port']]
    assert result['ip'] == '192.0.2.10'


async def test_unreadable_owned_state_refuses_app_connection(tmp_path, monkeypatch):
    async def failed(*args, **kwargs): return {'status':'error','errors':['owned state unavailable']}
    monkeypatch.setattr(compute, 'read_deployment', failed)
    result = await compute.load_step({**fixture(), 'workdir':str(tmp_path), 'blue/event':'delete'})
    assert result['blue/exit']==1 and result['blue/err']=='owned state unavailable'
    assert 'ip' not in result


async def test_digitalocean_ipv6_and_stun_remain_closed_except_requested_sources(tmp_path):
    from conftest import do_fixture
    opts={**do_fixture(),'workdir':str(tmp_path),'blue/event':'build'}
    await compute.compute_step(opts)
    node=json.loads((tmp_path/opts['profile']/'compute/nodes/0/node-none.tf.json').read_text())
    assert node['resource']['digitalocean_droplet']['node']['ipv6'] is False
    rule=next(r for r in compute.requirements(opts)['security']['ingress'] if r['id']=='stun')
    assert rule['protocol']=='udp' and rule['from_port']==3478 and rule['to_port']==3478

async def test_acceptance_ssh_uses_normalized_user_and_explicit_identity(monkeypatch):
    captured=[]
    async def output(args): captured.extend(args);return 'ok'
    monkeypatch.setattr(tools,'out',output)
    assert await tools.ssh_out({'ip':'192.0.2.3','user':'ubuntu','ssh-private-key-path':'/key with spaces'},'true')=='ok'
    assert captured[-4:]==['-i','/key with spaces','ubuntu@192.0.2.3','true']

def test_dns_backend_credentials_do_not_become_compute_environment():
    opts={**fixture(),'r2-access-key-id':'synthetic-access','r2-secret-access-key':'synthetic-secret','vultr-api-key':'compute-only'}
    assert tools.credential_env(opts,'provider-dns')=={'AWS_ACCESS_KEY_ID':'synthetic-access','AWS_SECRET_ACCESS_KEY':'synthetic-secret'}
    from package_agent_network_blue import validate
    assert validate.tofu_env(opts,'provider-compute')=={}
