project('gsm2sipd', 'c',
  version: '1.4.16',
  license: 'MIT',
  meson_version: '>= 0.60',
  default_options: ['c_std=c11', 'warning_level=2', 'buildtype=release', 'prefix=/usr'])

cc = meson.get_compiler('c')
add_project_arguments(
  '-D_GNU_SOURCE',
  '-DGSM2SIP_VERSION="@0@"'.format(meson.project_version()),
  '-Wno-unused-parameter',
  '-Wno-missing-field-initializers',
  language: 'c')

glib = dependency('glib-2.0', version: '>= 2.70')
gio = dependency('gio-2.0', version: '>= 2.70')
gio_unix = dependency('gio-unix-2.0')
alsa = dependency('alsa')
srtp = dependency('libsrtp2', required: get_option('srtp'))
libm = cc.find_library('m')

srtp_args = srtp.found() ? ['-DHAVE_SRTP=1'] : []
if not srtp.found()
  warning('libsrtp2 not found: srtp=true is ignored, calls use plain RTP')
endif

core = files('src/sipmsg.c', 'src/g722.c', 'src/dsp.c', 'src/gwutil.c', 'src/stun.c', 'src/sipclient.c')

executable('gsm2sipd',
  core + files('src/rtp.c', 'src/modem.c', 'src/bridge.c', 'src/main.c'),
  c_args: srtp_args,
  dependencies: [glib, gio, gio_unix, alsa, srtp, libm],
  install: true,
  install_dir: get_option('sbindir'))

install_data('data/gsm2sipd.service', install_dir: get_option('prefix') / 'lib' / 'systemd' / 'system')
install_data('data/gateway.conf.example', install_dir: get_option('sysconfdir') / 'gsm2sip')

# a cross build runs them only with an exe_wrapper (cross-arm64-qemu.ini)
if get_option('tests') and meson.can_run_host_binaries()
  t = executable('test-gsm2sip', core + files('tests/test_main.c'),
    include_directories: include_directories('src'),
    dependencies: [glib, gio, libm])
  test('unit', t)
endif
