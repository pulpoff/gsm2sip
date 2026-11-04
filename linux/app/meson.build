project('gsm2sip', 'c',
  version: '1.4.17',
  license: 'MIT',
  meson_version: '>= 0.62',
  default_options: ['c_std=gnu11', 'warning_level=2', 'buildtype=debugoptimized', 'prefix=/usr'])
# GObject callbacks take parameters they do not use, and `{ 0 }` initialisers are deliberate
add_project_arguments('-Wno-unused-parameter', '-Wno-missing-field-initializers', language: 'c')

gnome = import('gnome')
app_id = 'com.callagent.Gsm2sip'

# the API floor is the dev box's (GTK 4.18, libadwaita 1.7); the phone runs newer
deps = [
  dependency('gtk4', version: '>= 4.18'),
  dependency('libadwaita-1', version: '>= 1.7'),
  dependency('gio-unix-2.0'),
]

conf = configuration_data()
conf.set_quoted('GS_APP_ID', app_id)
conf.set_quoted('GS_VERSION', meson.project_version())
configure_file(output: 'gs-config.h', configuration: conf)

res = gnome.compile_resources('gs-resources', 'data/gsm2sip.gresource.xml',
  source_dir: 'data', c_name: 'gs')

executable('gsm2sip',
  files(
    'src/main.c',
    'src/gs-window.c',
    'src/gs-home.c',
    'src/gs-settings.c',
    'src/gs-logs.c',
    'src/gs-dialogs.c',
    'src/gs-gateway.c',
    'src/gs-phone-apps.c',
    'src/gs-net-info.c',
  ) + res,
  dependencies: deps,
  install: true)

# the netinfo module on its own, for checking its texts on a phone
executable('netinfo-dump', files('tools/netinfo-dump.c', 'src/gs-net-info.c'),
  include_directories: include_directories('src'),
  dependencies: dependency('gio-2.0'),
  install: false)

install_data('data/' + app_id + '.desktop', install_dir: get_option('datadir') / 'applications')
install_data('data/' + app_id + '.png', install_dir: get_option('datadir') / 'icons' / 'hicolor' / '512x512' / 'apps')
