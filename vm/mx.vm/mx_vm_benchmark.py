#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------
import os
import re
from os.path import dirname, join
from traceback import print_tb
import inspect
import subprocess

import mx
import mx_benchmark
import mx_sdk_vm
import mx_sdk_vm_impl

_suite = mx.suite('vm')
_native_image_vm_registry = mx_benchmark.VmRegistry('NativeImage', 'ni-vm')
_gu_vm_registry = mx_benchmark.VmRegistry('GraalUpdater', 'gu-vm')
_polybench_vm_registry = mx_benchmark.VmRegistry('PolyBench', 'polybench-vm')
_polybench_modes = [
    ('standard', ['--mode=standard']),
    ('interpreter', ['--mode=interpreter']),
]

class GraalVm(mx_benchmark.OutputCapturingJavaVm):
    def __init__(self, name, config_name, extra_java_args, extra_launcher_args):
        """
        :type name: str
        :type config_name: str
        :type extra_java_args: list[str] | None
        :type extra_launcher_args: list[str] | None
        """
        super(GraalVm, self).__init__()
        self._name = name
        self._config_name = config_name
        self.extra_java_args = extra_java_args or []
        self.extra_launcher_args = extra_launcher_args or []
        self.debug_args = mx.java_debug_args() if config_name == "jvm" else []

    def name(self):
        return self._name

    def config_name(self):
        return self._config_name

    def post_process_command_line_args(self, args):
        return self.extra_java_args + self.debug_args + args

    def post_process_launcher_command_line_args(self, args):
        return self.extra_launcher_args + \
               ['--vm.' + x[1:] if x.startswith('-X') else x for x in self.debug_args] + \
               args

    def home(self):
        return mx_sdk_vm_impl.graalvm_home(fatalIfMissing=True)

    def generate_java_command(self, args):
        return [os.path.join(self.home(), 'bin', 'java')] + args

    def run_java(self, args, out=None, err=None, cwd=None, nonZeroIsFatal=False):
        """Run 'java' workloads."""
        self.extract_vm_info(args)
        cmd = self.generate_java_command(args)
        cmd = mx.apply_command_mapper_hooks(cmd, self.command_mapper_hooks)
        return mx.run(cmd, out=out, err=err, cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)

    def run_lang(self, cmd, args, cwd):
        """Deprecated. Call 'run_launcher' instead."""
        mx.log_deprecation("'run_lang' is deprecated. Use 'run_launcher' instead.")
        return self.run_launcher(cmd, args, cwd)

    def run_launcher(self, cmd, args, cwd):
        """Run the 'cmd' command in the 'bin' directory."""
        args = self.post_process_launcher_command_line_args(args)
        self.extract_vm_info(args)
        mx.log("Running '{}' on '{}' with args: '{}'".format(cmd, self.name(), " ".join(args)))
        out = mx.TeeOutputCapture(mx.OutputCapture())
        command = [os.path.join(self.home(), 'bin', cmd)] + args
        command = mx.apply_command_mapper_hooks(command, self.command_mapper_hooks)
        code = mx.run(command, out=out, err=out, cwd=cwd, nonZeroIsFatal=False)
        out = out.underlying.data
        dims = self.dimensions(cwd, args, code, out)
        return code, out, dims


class NativeImageVM(GraalVm):
    """
    This is a VM that should be used for running all Native Image benchmarks. This VM should support all the benchmarks
    that a regular Java VM supports as it:
       1) Runs a benchmark with the Native Image Agent.
       2) Builds an image based on the configuration collected by the agent.
       3) Runs the image of the benchmark with supported VM arguments and with run-time arguments.
    """

    class BenchmarkConfig:
        def __init__(self, vm, bm_suite, args):
            self.bmSuite = bm_suite
            self.benchmark_suite_name = bm_suite.benchSuiteName(args) if len(inspect.getargspec(bm_suite.benchSuiteName).args) > 1 else bm_suite.benchSuiteName() # pylint: disable=deprecated-method
            self.benchmark_name = bm_suite.benchmarkName()
            self.executable, self.classpath_arguments, self.system_properties, cmd_line_image_run_args = NativeImageVM.extract_benchmark_arguments(args)
            self.extra_image_build_arguments = bm_suite.extra_image_build_argument(self.benchmark_name, args)
            # use list() to create fresh copies to safeguard against accidental modification
            self.image_run_args = bm_suite.extra_run_arg(self.benchmark_name, args, list(cmd_line_image_run_args))
            self.extra_agent_run_args = bm_suite.extra_agent_run_arg(self.benchmark_name, args, list(cmd_line_image_run_args))
            self.extra_profile_run_args = bm_suite.extra_profile_run_arg(self.benchmark_name, args, list(cmd_line_image_run_args))
            self.extra_agent_profile_run_args = bm_suite.extra_agent_profile_run_arg(self.benchmark_name, args, list(cmd_line_image_run_args))
            self.benchmark_output_dir = bm_suite.benchmark_output_dir(self.benchmark_name, args)
            self.pgo_iteration_num = None
            self.params = ['extra-image-build-argument', 'extra-run-arg', 'extra-agent-run-arg', 'extra-profile-run-arg',
                           'extra-agent-profile-run-arg', 'benchmark-output-dir', 'stages', 'skip-agent-assertions']
            self.profile_file_extension = '.iprof'
            self.stages = bm_suite.stages(args)
            self.last_stage = self.stages[-1]
            self.skip_agent_assertions = bm_suite.skip_agent_assertions(self.benchmark_name, args)
            self.root_dir = self.benchmark_output_dir if self.benchmark_output_dir else mx.suite('vm').get_output_root(platformDependent=False, jdkDependent=False)
            self.executable_suffix = ('-' + self.benchmark_name) if self.benchmark_name else ''
            self.executable_name = (os.path.splitext(os.path.basename(self.executable[1]))[0] + self.executable_suffix if self.executable[0] == '-jar' else self.executable[0] + self.executable_suffix).lower()
            self.final_image_name = self.executable_name + '-' + vm.config_name()
            self.output_dir = mx.join(os.path.abspath(self.root_dir), 'native-image-benchmarks', self.executable_name + '-' + vm.config_name())
            self.profile_path_no_extension = os.path.join(self.output_dir, self.executable_name)
            self.latest_profile_path = self.profile_path_no_extension + '-latest' + self.profile_file_extension
            self.config_dir = os.path.join(self.output_dir, 'config')
            self.log_dir = self.output_dir
            self.analysis_report_path = os.path.join(self.output_dir, self.executable_name + '-analysis.json')
            self.image_build_report_path = os.path.join(self.output_dir, self.executable_name + '-image-build-stats.json')
            self.base_image_build_args = [os.path.join(mx_sdk_vm_impl.graalvm_home(fatalIfMissing=True), 'bin', 'native-image')]
            self.base_image_build_args += ['--no-fallback', '-g', '--allow-incomplete-classpath', '--no-server', '--enable-all-security-services', '-H:DeadlockWatchdogInterval=30']
            self.base_image_build_args += ['-H:+VerifyGraalGraphs', '-H:+VerifyPhases', '--diagnostics-mode'] if vm.is_gate else []
            self.base_image_build_args += ['-J-ea', '-J-esa'] if vm.is_gate and not bm_suite.skip_build_assertions(self.benchmark_name) else []

            self.base_image_build_args += self.system_properties
            self.base_image_build_args += self.classpath_arguments
            self.base_image_build_args += self.executable
            self.base_image_build_args += ['-H:Path=' + self.output_dir]
            self.base_image_build_args += ['-H:ConfigurationFileDirectories=' + self.config_dir]
            self.base_image_build_args += ['-H:+PrintAnalysisStatistics', '-H:AnalysisStatisticsFile=' + self.analysis_report_path]
            self.base_image_build_args += ['-H:+CollectImageBuildStatistics', '-H:ImageBuildStatisticsFile=' + self.image_build_report_path]
            if vm.is_llvm:
                self.base_image_build_args += ['-H:CompilerBackend=llvm', '-H:Features=org.graalvm.home.HomeFinderFeature', '-H:DeadlockWatchdogInterval=0']
            if vm.gc:
                self.base_image_build_args += ['--gc=' + vm.gc, '-H:+SpawnIsolates']
            if vm.native_architecture:
                self.base_image_build_args += ['-H:+NativeArchitecture']
            self.base_image_build_args += self.extra_image_build_arguments

    def __init__(self, name, config_name, extra_java_args=None, extra_launcher_args=None,
                 pgo_aot_inline=False, pgo_instrumented_iterations=0, pgo_inline_explored=False, hotspot_pgo=False,
                 is_gate=False, is_llvm=False, pgo_context_sensitive=True, gc=None, native_architecture=False,
                 collect_jdk_cache=False, use_jdk_cache=False, mlpgo_model=None, mlpgo_polite=False, mlpgo_multi_branch=False):
        super(NativeImageVM, self).__init__(name, config_name, extra_java_args, extra_launcher_args)
        self.pgo_aot_inline = pgo_aot_inline
        self.pgo_instrumented_iterations = pgo_instrumented_iterations
        self.pgo_context_sensitive = pgo_context_sensitive
        self.pgo_inline_explored = pgo_inline_explored
        self.hotspot_pgo = hotspot_pgo
        self.is_gate = is_gate
        self.is_llvm = is_llvm
        self.gc = gc
        self.native_architecture = native_architecture
        self.collect_jdk_cache, self.use_jdk_cache = collect_jdk_cache, use_jdk_cache
        assert mlpgo_model in [None, 'tree' , 'knn', 'dnn', 'mi-dnn']
        self.mlpgo_model, self.mlpgo_polite, self.mlpgo_multi_branch = mlpgo_model, mlpgo_polite, mlpgo_multi_branch

    @staticmethod
    def supported_vm_arg_prefixes():
        """
            This list is intentionally restrictive. We want to be sure that what we add is correct on the case-by-case
            basis. In the future we can convert this from a failure into a warning.
            :return: a list of args supported by native image.
        """
        return ['-D', '-Xmx', '-Xmn', '-XX:-PrintGC', '-XX:+PrintGC']

    _VM_OPTS_SPACE_SEPARATED_ARG = ['-mp', '-modulepath', '-limitmods', '-addmods', '-upgrademodulepath', '-m',
                                    '--module-path', '--limit-modules', '--add-modules', '--upgrade-module-path',
                                    '--module', '--module-source-path', '--add-exports', '--add-reads',
                                    '--patch-module', '--boot-class-path', '--source-path', '-cp', '-classpath']

    @staticmethod
    def _split_vm_arguments(args):
        i = 0
        while i < len(args):
            arg = args[i]
            if arg == '-jar':
                return args[:i], args[i:i + 2], args[i + 2:]
            elif not arg.startswith('-'):
                return args[:i], [args[i]], args[i + 1:]
            elif arg in NativeImageVM._VM_OPTS_SPACE_SEPARATED_ARG:
                i += 2
            else:
                i += 1

        mx.abort('No executable found in args: ' + str(args))

    @staticmethod
    def extract_benchmark_arguments(args):
        i = 0
        clean_args = args[:]
        while i < len(args):
            if args[i].startswith('--jvmArgsPrepend'):
                clean_args[i + 1] = ' '.join([x for x in args[i + 1].split(' ') if "-Dnative-image" not in x])
                i += 2
            else:
                i += 1
        clean_args = [x for x in clean_args if "-Dnative-image" not in x]
        vm_args, executable, image_run_args = NativeImageVM._split_vm_arguments(clean_args)

        classpath_arguments = []
        system_properties = [a for a in vm_args if a.startswith('-D')]
        image_vm_args = []
        i = 0
        while i < len(vm_args):
            vm_arg = vm_args[i]
            if vm_arg.startswith('--class-path'):
                classpath_arguments.append(vm_arg)
                i += 1
            elif vm_arg.startswith('-cp') or vm_arg.startswith('-classpath'):
                classpath_arguments += [vm_arg, vm_args[i + 1]]
                i += 2
            else:
                if not any(vm_arg.startswith(elem) for elem in NativeImageVM.supported_vm_arg_prefixes()):
                    mx.abort('Unsupported argument ' + vm_arg + '.' +
                             ' Currently supported argument prefixes are: ' + str(NativeImageVM.supported_vm_arg_prefixes()))
                image_vm_args.append(vm_arg)
                i += 1

        return executable, classpath_arguments, system_properties, image_vm_args + image_run_args

    class Stages:
        def __init__(self, config, bench_out, bench_err, is_gate, non_zero_is_fatal, cwd):
            self.stages_till_now = []
            self.config = config
            self.bench_out = bench_out
            self.bench_err = bench_err
            self.final_image_name = config.final_image_name
            self.is_gate = is_gate
            self.non_zero_is_fatal = non_zero_is_fatal
            self.cwd = cwd
            self.failed = False

            self.current_stage = ''
            self.exit_code = None
            self.command = None
            self.stderr_path = None
            self.stdout_path = None

        def reset_stage(self):
            self.current_stage = ''
            self.exit_code = None
            self.command = None
            self.stderr_path = None
            self.stdout_path = None

        def __enter__(self):
            self.stdout_path = os.path.abspath(os.path.join(self.config.log_dir, self.final_image_name + '-' + self.current_stage + '-stdout.log'))
            self.stderr_path = os.path.abspath(os.path.join(self.config.log_dir, self.final_image_name + '-' + self.current_stage + '-stderr.log'))
            self.stdout_file = open(self.stdout_path, 'w')
            self.stderr_file = open(self.stderr_path, 'w')

            self.separator_line()
            mx.log('Entering stage: ' + self.current_stage + ' for ' + self.final_image_name)
            self.separator_line()

            mx.log('Running: ')
            mx.log(' '.join(self.command))

            if self.stdout_path:
                mx.log('The standard output is saved to ' + str(self.stdout_path))
            if self.stderr_path:
                mx.log('The standard error is saved to ' + str(self.stderr_path))

            return self

        def __exit__(self, tp, value, tb):
            self.stdout_file.flush()
            self.stderr_file.flush()

            if self.exit_code == 0 and (tb is None):
                if self.current_stage.startswith(self.config.last_stage):
                    self.bench_out('Successfully finished the last specified stage:' + ' ' + self.current_stage + ' for ' + self.final_image_name)
                else:
                    mx.log('Successfully finished stage:' + ' ' + self.current_stage)

                self.separator_line()
            else:
                self.failed = True
                if self.exit_code is not None and self.exit_code != 0:
                    mx.log(mx.colorize('Failed in stage ' + self.current_stage + ' for ' + self.final_image_name + ' with exit code ' + str(self.exit_code), 'red'))
                    if self.stdout_path:
                        mx.log(mx.colorize('--------- Standard output:', 'blue'))
                        with open(self.stdout_path, 'r') as stdout:
                            mx.log(stdout.read())

                    if self.stderr_path:
                        mx.log(mx.colorize('--------- Standard error:', 'red'))
                        with open(self.stderr_path, 'r') as stderr:
                            mx.log(stderr.read())

                if tb:
                    mx.log(mx.colorize('Failed in stage ' + self.current_stage + ' with ', 'red'))
                    print_tb(tb)

                self.separator_line()

                if len(self.stages_till_now) > 0:
                    mx.log(mx.colorize('--------- To run the failed benchmark execute the following: ', 'green'))
                    mx.log(mx.current_mx_command())

                    if len(self.stages_till_now[:-1]) > 0:
                        mx.log(mx.colorize('--------- To only prepare the benchmark add the following to the end of the previous command: ', 'green'))
                        mx.log('-Dnative-image.benchmark.stages=' + ','.join(self.stages_till_now[:-1]))

                    mx.log(mx.colorize('--------- To only run the failed stage add the following to the end of the previous command: ', 'green'))
                    mx.log('-Dnative-image.benchmark.stages=' + self.current_stage)

                    mx.log(mx.colorize('--------- Additional arguments that can be used for debugging the benchmark go after the final --: ', 'green'))
                    for param in self.config.params:
                        mx.log('-Dnative-image.benchmark.' + param + '=')

                self.separator_line()
                if self.non_zero_is_fatal:
                    mx.abort('Exiting the benchmark due to the failure.')

            self.stdout_file.close()
            self.stderr_file.close()
            self.reset_stage()

        def stdout(self, include_bench_out=False):
            def writeFun(s):
                v = self.stdout_file.write(s)
                if include_bench_out:
                    self.bench_out(s)
                else:
                    mx.logv(s, end='')
                return v
            return writeFun

        def stderr(self, include_bench_err=False):
            def writeFun(s):
                v = self.stdout_file.write(s)
                if include_bench_err:
                    self.bench_err(s)
                else:
                    mx.logv(s, end='')
                return v
            return writeFun

        def change_stage(self, *argv):
            if self.failed:
                return False

            stage_name = '-'.join(argv)
            self.stages_till_now.append(stage_name)
            self.current_stage = stage_name
            stage_applies = argv[0] in self.config.stages or stage_name in self.config.stages
            return stage_applies

        @staticmethod
        def separator_line():
            mx.log(mx.colorize('-' * 120, 'green'))

        def set_command(self, command):
            self.command = command
            return self

        def execute_command(self, vm=None):
            write_output = self.current_stage == 'run' or self.current_stage == 'image' or self.is_gate
            cmd = self.command
            self.exit_code = self.config.bmSuite.run_stage(vm, self.current_stage, cmd, self.stdout(write_output), self.stderr(write_output), self.cwd, False)
            if "image" not in self.current_stage and self.config.bmSuite.validateReturnCode(self.exit_code):
                self.exit_code = 0

    def image_build_statistics_rules(self, benchmark):
        objects_list = ["total_array_store",
                          "total_assertion_error_nullary",
                          "total_assertion_error_object",
                          "total_class_cast",
                          "total_division_by_zero",
                          "total_illegal_argument_exception_argument_is_not_an_array",
                          "total_illegal_argument_exception_negative_length",
                          "total_integer_exact_overflow",
                          "total_long_exact_overflow",
                          "total_null_pointer",
                          "total_out_of_bounds"]
        metric_objects = ["total_devirtualized_invokes"]
        for obj in objects_list:
            metric_objects.append(obj + "_after_parse_canonicalization")
            metric_objects.append(obj + "_before_high_tier")
            metric_objects.append(obj + "_after_high_tier")
        rules = []
        for i in range(0, len(metric_objects)):
            rules.append(mx_benchmark.JsonStdOutFileRule(r'^# Printing image build statistics to: (?P<path>\S+?)$', 'path', {
                "benchmark": benchmark,
                "metric.name": "image-build-stats",
                "metric.type": "numeric",
                "metric.unit": "#",
                "metric.value": ("<"+metric_objects[i]+">", long),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": metric_objects[i].replace("_", "-").replace("total-", ""),
            }, [metric_objects[i]]))
        return rules

    def rules(self, output, benchmarks, bmSuiteArgs):
        class NativeImageTimeToInt(object):
            def __call__(self, *args, **kwargs):
                return int(float(args[0].replace(',', '')))

        class NativeImageHexToInt(object):
            def __call__(self, *args, **kwargs):
                return int(args[0], 16)

        return [
            mx_benchmark.StdOutRule(
                r"The executed image size for benchmark (?P<bench_suite>[a-zA-Z0-9_\-]+):(?P<benchmark>[a-zA-Z0-9_\-]+) is (?P<value>[0-9]+) B",
                {
                    "bench-suite": ("<bench_suite>", str),
                    "benchmark": ("<benchmark>", str),
                    "vm": "svm",
                    "metric.name": "binary-size",
                    "metric.value": ("<value>", int),
                    "metric.unit": "B",
                    "metric.type": "numeric",
                    "metric.score-function": "id",
                    "metric.better": "lower",
                    "metric.iteration": 0,
                }),
            mx_benchmark.StdOutRule(
                r"The (?P<type>[a-zA-Z0-9_\-]+) configuration size for benchmark (?P<bench_suite>[a-zA-Z0-9_\-]+):(?P<benchmark>[a-zA-Z0-9_\-]+) is (?P<value>[0-9]+) B",
                {
                    "bench-suite": ("<bench_suite>", str),
                    "benchmark": ("<benchmark>", str),
                    "vm": "svm",
                    "metric.name": "config-size",
                    "metric.value": ("<value>", int),
                    "metric.unit": "B",
                    "metric.type": "numeric",
                    "metric.score-function": "id",
                    "metric.better": "lower",
                    "metric.iteration": 0,
                    "metric.object": ("<type>", str)
                }),
            mx_benchmark.StdOutRule(r'^\[\S+:[0-9]+\][ ]+\[total\]:[ ]+(?P<time>[0-9,.]+?) ms', {
                "benchmark": benchmarks[0],
                "metric.name": "compile-time",
                "metric.type": "numeric",
                "metric.unit": "ms",
                "metric.value": ("<time>", NativeImageTimeToInt()),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": "total",
            }),
            mx_benchmark.StdOutRule(r'^\[\S+:[0-9]+\][ ]+(?P<phase>\w+?):[ ]+(?P<time>[0-9,.]+?) ms', {
                "benchmark": benchmarks[0],
                "metric.name": "compile-time",
                "metric.type": "numeric",
                "metric.unit": "ms",
                "metric.value": ("<time>", NativeImageTimeToInt()),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": ("<phase>", str),
            }),
            mx_benchmark.StdOutRule(r'^[ ]*[0-9]+[ ]+.(?P<section>[a-zA-Z0-9._-]+?)[ ]+(?P<size>[0-9a-f]+?)[ ]+', {
                "benchmark": benchmarks[0],
                "metric.name": "binary-section-size",
                "metric.type": "numeric",
                "metric.unit": "B",
                "metric.value": ("<size>", NativeImageHexToInt()),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": ("<section>", str),
            }),
            mx_benchmark.JsonStdOutFileRule(r'^# Printing analysis results stats to: (?P<path>\S+?)$', 'path', {
                "benchmark": benchmarks[0],
                "metric.name": "analysis-stats",
                "metric.type": "numeric",
                "metric.unit": "#",
                "metric.value": ("<total_reachable_types>", int),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": "reachable-types",
            }, ['total_reachable_types']),
            mx_benchmark.JsonStdOutFileRule(r'^# Printing analysis results stats to: (?P<path>\S+?)$', 'path', {
                "benchmark": benchmarks[0],
                "metric.name": "analysis-stats",
                "metric.type": "numeric",
                "metric.unit": "#",
                "metric.value": ("<total_reachable_methods>", int),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": "reachable-methods",
            }, ['total_reachable_methods']),
            mx_benchmark.JsonStdOutFileRule(r'^# Printing analysis results stats to: (?P<path>\S+?)$', 'path', {
                "benchmark": benchmarks[0],
                "metric.name": "analysis-stats",
                "metric.type": "numeric",
                "metric.unit": "#",
                "metric.value": ("<total_reachable_fields>", int),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": "reachable-fields",
            }, ['total_reachable_fields']),
            mx_benchmark.JsonStdOutFileRule(r'^# Printing analysis results stats to: (?P<path>\S+?)$', 'path', {
                "benchmark": benchmarks[0],
                "metric.name": "analysis-stats",
                "metric.type": "numeric",
                "metric.unit": "B",
                "metric.value": ("<total_memory_bytes>", int),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
                "metric.object": "memory"
            }, ['total_memory_bytes'])
        ] + self.image_build_statistics_rules(benchmarks[0])

    def run_stage_agent(self, config, stages):
        profile_path = config.profile_path_no_extension + '-agent' + config.profile_file_extension
        hotspot_vm_args = ['-ea', '-esa'] if self.is_gate and not config.skip_agent_assertions else []
        hotspot_run_args = []
        hotspot_vm_args += ['-agentlib:native-image-agent=config-output-dir=' + str(config.config_dir), '-XX:-UseJVMCINativeLibrary']

        if self.hotspot_pgo:
            hotspot_vm_args += ['-Dgraal.PGOInstrument=' + profile_path]

        if self.hotspot_pgo and not self.is_gate and config.extra_agent_profile_run_args:
            hotspot_run_args += config.extra_agent_profile_run_args
        else:
            hotspot_run_args += config.extra_agent_run_args

        hotspot_args = hotspot_vm_args + config.classpath_arguments + config.executable + config.system_properties + hotspot_run_args
        java_command = os.path.join(mx_sdk_vm_impl.graalvm_home(fatalIfMissing=True), 'bin', 'java')
        with stages.set_command([java_command] + hotspot_args) as s:
            s.execute_command()
            if self.hotspot_pgo and s.exit_code == 0:
                # Hotspot instrumentation does not produce profiling information for the helloworld benchmark
                if os.path.exists(profile_path):
                    mx.copyfile(profile_path, config.latest_profile_path)
                else:
                    mx.warn("No profile information emitted during agent run.")

    def run_stage_instrument_image(self, config, stages, out, i, instrumentation_image_name, image_path, image_path_latest, instrumented_iterations):
        executable_name_args = ['-H:Name=' + instrumentation_image_name]
        pgo_verification_output_path = os.path.join(config.output_dir, instrumentation_image_name + '-probabilities.log')
        pgo_args = ['--pgo=' + config.latest_profile_path, '-H:+VerifyPGOProfiles', '-H:VerificationDumpFile=' + pgo_verification_output_path]
        pgo_args += ['-H:' + ('+' if self.pgo_context_sensitive else '-') + 'EnablePGOContextSensitivity']
        pgo_args += ['-H:+AOTInliner'] if self.pgo_aot_inline else ['-H:-AOTInliner']
        instrument_args = ['--pgo-instrument'] + ([] if i == 0 else pgo_args)
        instrument_args += ['-H:+InlineAllExplored'] if self.pgo_inline_explored else []
        if self.collect_jdk_cache:
            instrument_args += ['-H:ProfilesPackagePrefixes=com.sun.accessibility.internal.resources,com.sun.awt,'
                                'com.sun.beans,com.sun.beans.decoder,com.sun.beans.editors,com.sun.beans.finder,'
                                'com.sun.beans.infos,com.sun.beans.introspect,com.sun.beans.util,'
                                'com.sun.crypto.provider,com.sun.imageio.plugins.bmp,com.sun.imageio.plugins.common,'
                                'com.sun.imageio.plugins.gif,com.sun.imageio.plugins.jpeg,'
                                'com.sun.imageio.plugins.png,com.sun.imageio.plugins.tiff,'
                                'com.sun.imageio.plugins.wbmp,com.sun.imageio.spi,com.sun.imageio.stream,'
                                'com.sun.jarsigner,com.sun.java.accessibility.util,'
                                'com.sun.java.accessibility.util.internal,com.sun.java.swing,'
                                'com.sun.java.swing.plaf.gtk,com.sun.java.swing.plaf.gtk.icons,'
                                'com.sun.java.swing.plaf.gtk.resources,com.sun.java.swing.plaf.motif,'
                                'com.sun.java.swing.plaf.motif.icons,com.sun.java.swing.plaf.motif.resources,'
                                'com.sun.java.util.jar.pack,com.sun.java_cup.internal.runtime,com.sun.javadoc,'
                                'com.sun.jdi,com.sun.jdi.connect,com.sun.jdi.connect.spi,com.sun.jdi.event,'
                                'com.sun.jdi.request,com.sun.jmx.defaults,com.sun.jmx.interceptor,'
                                'com.sun.jmx.mbeanserver,com.sun.jmx.remote.internal,com.sun.jmx.remote.internal.rmi,'
                                'com.sun.jmx.remote.protocol.rmi,com.sun.jmx.remote.security,com.sun.jmx.remote.util,'
                                'com.sun.jndi.dns,com.sun.jndi.ldap,com.sun.jndi.ldap.dns,com.sun.jndi.ldap.ext,'
                                'com.sun.jndi.ldap.pool,com.sun.jndi.ldap.sasl,com.sun.jndi.ldap.spi,'
                                'com.sun.jndi.rmi.registry,com.sun.jndi.toolkit.ctx,com.sun.jndi.toolkit.dir,'
                                'com.sun.jndi.toolkit.url,com.sun.jndi.url.dns,com.sun.jndi.url.ldap,'
                                'com.sun.jndi.url.ldaps,com.sun.jndi.url.rmi,com.sun.management,'
                                'com.sun.management.internal,com.sun.media.sound,com.sun.naming.internal,'
                                'com.sun.net.httpserver,com.sun.net.httpserver.spi,com.sun.net.ssl,'
                                'com.sun.net.ssl.internal.ssl,com.sun.net.ssl.internal.www.protocol.https,'
                                'com.sun.nio.file,com.sun.nio.sctp,com.sun.org.apache.bcel.internal,'
                                'com.sun.org.apache.bcel.internal.classfile,com.sun.org.apache.bcel.internal.generic,'
                                'com.sun.org.apache.bcel.internal.util,com.sun.org.apache.xalan.internal,'
                                'com.sun.org.apache.xalan.internal.extensions,com.sun.org.apache.xalan.internal.lib,'
                                'com.sun.org.apache.xalan.internal.res,com.sun.org.apache.xalan.internal.templates,'
                                'com.sun.org.apache.xalan.internal.utils,com.sun.org.apache.xalan.internal.xsltc,'
                                'com.sun.org.apache.xalan.internal.xsltc.compiler,'
                                'com.sun.org.apache.xalan.internal.xsltc.compiler.util,'
                                'com.sun.org.apache.xalan.internal.xsltc.dom,'
                                'com.sun.org.apache.xalan.internal.xsltc.runtime,'
                                'com.sun.org.apache.xalan.internal.xsltc.runtime.output,'
                                'com.sun.org.apache.xalan.internal.xsltc.trax,'
                                'com.sun.org.apache.xalan.internal.xsltc.util,com.sun.org.apache.xerces.internal.dom,'
                                'com.sun.org.apache.xerces.internal.dom.events,'
                                'com.sun.org.apache.xerces.internal.impl,com.sun.org.apache.xerces.internal.impl.dtd,'
                                'com.sun.org.apache.xerces.internal.impl.dtd.models,'
                                'com.sun.org.apache.xerces.internal.impl.dv,'
                                'com.sun.org.apache.xerces.internal.impl.dv.dtd,'
                                'com.sun.org.apache.xerces.internal.impl.dv.util,'
                                'com.sun.org.apache.xerces.internal.impl.dv.xs,'
                                'com.sun.org.apache.xerces.internal.impl.io,'
                                'com.sun.org.apache.xerces.internal.impl.msg,'
                                'com.sun.org.apache.xerces.internal.impl.validation,'
                                'com.sun.org.apache.xerces.internal.impl.xpath,'
                                'com.sun.org.apache.xerces.internal.impl.xpath.regex,'
                                'com.sun.org.apache.xerces.internal.impl.xs,'
                                'com.sun.org.apache.xerces.internal.impl.xs.identity,'
                                'com.sun.org.apache.xerces.internal.impl.xs.models,'
                                'com.sun.org.apache.xerces.internal.impl.xs.opti,'
                                'com.sun.org.apache.xerces.internal.impl.xs.traversers,'
                                'com.sun.org.apache.xerces.internal.impl.xs.util,'
                                'com.sun.org.apache.xerces.internal.jaxp,'
                                'com.sun.org.apache.xerces.internal.jaxp.datatype,'
                                'com.sun.org.apache.xerces.internal.jaxp.validation,'
                                'com.sun.org.apache.xerces.internal.parsers,com.sun.org.apache.xerces.internal.util,'
                                'com.sun.org.apache.xerces.internal.utils,'
                                'com.sun.org.apache.xerces.internal.xinclude,com.sun.org.apache.xerces.internal.xni,'
                                'com.sun.org.apache.xerces.internal.xni.grammars,'
                                'com.sun.org.apache.xerces.internal.xni.parser,'
                                'com.sun.org.apache.xerces.internal.xpointer,com.sun.org.apache.xerces.internal.xs,'
                                'com.sun.org.apache.xerces.internal.xs.datatypes,com.sun.org.apache.xml.internal.dtm,'
                                'com.sun.org.apache.xml.internal.dtm.ref,'
                                'com.sun.org.apache.xml.internal.dtm.ref.dom2dtm,'
                                'com.sun.org.apache.xml.internal.dtm.ref.sax2dtm,com.sun.org.apache.xml.internal.res,'
                                'com.sun.org.apache.xml.internal.security,'
                                'com.sun.org.apache.xml.internal.security.algorithms,'
                                'com.sun.org.apache.xml.internal.security.algorithms.implementations,'
                                'com.sun.org.apache.xml.internal.security.c14n,'
                                'com.sun.org.apache.xml.internal.security.c14n.helper,'
                                'com.sun.org.apache.xml.internal.security.c14n.implementations,'
                                'com.sun.org.apache.xml.internal.security.exceptions,'
                                'com.sun.org.apache.xml.internal.security.keys,'
                                'com.sun.org.apache.xml.internal.security.keys.content,'
                                'com.sun.org.apache.xml.internal.security.keys.content.keyvalues,'
                                'com.sun.org.apache.xml.internal.security.keys.content.x509,'
                                'com.sun.org.apache.xml.internal.security.keys.keyresolver,'
                                'com.sun.org.apache.xml.internal.security.keys.keyresolver.implementations,'
                                'com.sun.org.apache.xml.internal.security.keys.storage,'
                                'com.sun.org.apache.xml.internal.security.keys.storage.implementations,'
                                'com.sun.org.apache.xml.internal.security.resource,'
                                'com.sun.org.apache.xml.internal.security.signature,'
                                'com.sun.org.apache.xml.internal.security.signature.reference,'
                                'com.sun.org.apache.xml.internal.security.transforms,'
                                'com.sun.org.apache.xml.internal.security.transforms.implementations,'
                                'com.sun.org.apache.xml.internal.security.transforms.params,'
                                'com.sun.org.apache.xml.internal.security.utils,'
                                'com.sun.org.apache.xml.internal.security.utils.resolver,'
                                'com.sun.org.apache.xml.internal.security.utils.resolver.implementations,'
                                'com.sun.org.apache.xml.internal.serialize,'
                                'com.sun.org.apache.xml.internal.serializer,'
                                'com.sun.org.apache.xml.internal.serializer.dom3,'
                                'com.sun.org.apache.xml.internal.serializer.utils,'
                                'com.sun.org.apache.xml.internal.utils,com.sun.org.apache.xml.internal.utils.res,'
                                'com.sun.org.apache.xpath.internal,com.sun.org.apache.xpath.internal.axes,'
                                'com.sun.org.apache.xpath.internal.compiler,'
                                'com.sun.org.apache.xpath.internal.functions,com.sun.org.apache.xpath.internal.jaxp,'
                                'com.sun.org.apache.xpath.internal.objects,'
                                'com.sun.org.apache.xpath.internal.operations,'
                                'com.sun.org.apache.xpath.internal.patterns,com.sun.org.apache.xpath.internal.res,'
                                'com.sun.org.slf4j.internal,com.sun.rmi.rmid,com.sun.rowset,com.sun.rowset.internal,'
                                'com.sun.rowset.providers,com.sun.security.auth,com.sun.security.auth.callback,'
                                'com.sun.security.auth.login,com.sun.security.auth.module,'
                                'com.sun.security.cert.internal.x509,com.sun.security.jgss,com.sun.security.ntlm,'
                                'com.sun.security.sasl,com.sun.security.sasl.digest,com.sun.security.sasl.gsskerb,'
                                'com.sun.security.sasl.ntlm,com.sun.security.sasl.util,com.sun.source.doctree,'
                                'com.sun.source.tree,com.sun.source.util,com.sun.swing.internal.plaf.basic.resources,'
                                'com.sun.swing.internal.plaf.metal.resources,'
                                'com.sun.swing.internal.plaf.synth.resources,com.sun.tools.attach,'
                                'com.sun.tools.attach.spi,com.sun.tools.classfile,com.sun.tools.doclets.standard,'
                                'com.sun.tools.doclint,com.sun.tools.doclint.resources,'
                                'com.sun.tools.example.debug.expr,com.sun.tools.example.debug.tty,'
                                'com.sun.tools.javac,com.sun.tools.javac.api,com.sun.tools.javac.code,'
                                'com.sun.tools.javac.comp,com.sun.tools.javac.file,com.sun.tools.javac.jvm,'
                                'com.sun.tools.javac.launcher,com.sun.tools.javac.main,com.sun.tools.javac.model,'
                                'com.sun.tools.javac.parser,com.sun.tools.javac.platform,'
                                'com.sun.tools.javac.processing,com.sun.tools.javac.resources,'
                                'com.sun.tools.javac.tree,com.sun.tools.javac.util,com.sun.tools.javadoc,'
                                'com.sun.tools.javadoc.main,com.sun.tools.javadoc.resources,com.sun.tools.javap,'
                                'com.sun.tools.javap.resources,com.sun.tools.jconsole,com.sun.tools.jdeprscan,'
                                'com.sun.tools.jdeprscan.resources,com.sun.tools.jdeprscan.scan,com.sun.tools.jdeps,'
                                'com.sun.tools.jdeps.resources,com.sun.tools.jdi,com.sun.tools.jdi.resources,'
                                'com.sun.tools.script.shell,com.sun.tools.sjavac,com.sun.tools.sjavac.client,'
                                'com.sun.tools.sjavac.comp,com.sun.tools.sjavac.comp.dependencies,'
                                'com.sun.tools.sjavac.options,com.sun.tools.sjavac.pubapi,'
                                'com.sun.tools.sjavac.server,com.sun.tools.sjavac.server.log,'
                                'com.sun.xml.internal.stream,com.sun.xml.internal.stream.dtd,'
                                'com.sun.xml.internal.stream.dtd.nonvalidating,com.sun.xml.internal.stream.events,'
                                'com.sun.xml.internal.stream.util,com.sun.xml.internal.stream.writers,java.applet,'
                                'java.awt,java.awt.color,java.awt.datatransfer,java.awt.desktop,java.awt.dnd,'
                                'java.awt.dnd.peer,java.awt.event,java.awt.font,java.awt.geom,java.awt.im,'
                                'java.awt.im.spi,java.awt.image,java.awt.image.renderable,java.awt.peer,'
                                'java.awt.print,java.beans,java.beans.beancontext,java.io,java.lang,'
                                'java.lang.annotation,java.lang.instrument,java.lang.invoke,java.lang.management,'
                                'java.lang.module,java.lang.ref,java.lang.reflect,java.math,java.net,java.net.http,'
                                'java.net.spi,java.nio,java.nio.channels,java.nio.channels.spi,java.nio.charset,'
                                'java.nio.charset.spi,java.nio.file,java.nio.file.attribute,java.nio.file.spi,'
                                'java.rmi,java.rmi.activation,java.rmi.dgc,java.rmi.registry,java.rmi.server,'
                                'java.security,java.security.acl,java.security.cert,java.security.interfaces,'
                                'java.security.spec,java.sql,java.text,java.text.spi,java.time,java.time.chrono,'
                                'java.time.format,java.time.temporal,java.time.zone,java.util,java.util.concurrent,'
                                'java.util.concurrent.atomic,java.util.concurrent.locks,java.util.function,'
                                'java.util.jar,java.util.logging,java.util.prefs,java.util.regex,java.util.spi,'
                                'java.util.stream,java.util.zip,javax.accessibility,javax.annotation.processing,'
                                'javax.crypto,javax.crypto.interfaces,javax.crypto.spec,javax.imageio,'
                                'javax.imageio.event,javax.imageio.metadata,javax.imageio.plugins.bmp,'
                                'javax.imageio.plugins.jpeg,javax.imageio.plugins.tiff,javax.imageio.spi,'
                                'javax.imageio.stream,javax.lang.model,javax.lang.model.element,'
                                'javax.lang.model.type,javax.lang.model.util,javax.management,'
                                'javax.management.loading,javax.management.modelmbean,javax.management.monitor,'
                                'javax.management.openmbean,javax.management.relation,javax.management.remote,'
                                'javax.management.remote.rmi,javax.management.timer,javax.naming,'
                                'javax.naming.directory,javax.naming.event,javax.naming.ldap,javax.naming.spi,'
                                'javax.net,javax.net.ssl,javax.print,javax.print.attribute,'
                                'javax.print.attribute.standard,javax.print.event,javax.rmi.ssl,javax.script,'
                                'javax.security.auth,javax.security.auth.callback,javax.security.auth.kerberos,'
                                'javax.security.auth.login,javax.security.auth.spi,javax.security.auth.x500,'
                                'javax.security.cert,javax.security.sasl,javax.smartcardio,javax.sound.midi,'
                                'javax.sound.midi.spi,javax.sound.sampled,javax.sound.sampled.spi,javax.sql,'
                                'javax.sql.rowset,javax.sql.rowset.serial,javax.sql.rowset.spi,javax.swing,'
                                'javax.swing.beaninfo.images,javax.swing.border,javax.swing.colorchooser,'
                                'javax.swing.event,javax.swing.filechooser,javax.swing.plaf,javax.swing.plaf.basic,'
                                'javax.swing.plaf.basic.icons,javax.swing.plaf.metal,javax.swing.plaf.metal.icons,'
                                'javax.swing.plaf.metal.icons.ocean,javax.swing.plaf.metal.sounds,'
                                'javax.swing.plaf.multi,javax.swing.plaf.nimbus,javax.swing.plaf.synth,'
                                'javax.swing.table,javax.swing.text,javax.swing.text.html,'
                                'javax.swing.text.html.parser,javax.swing.text.rtf,javax.swing.text.rtf.charsets,'
                                'javax.swing.tree,javax.swing.undo,javax.tools,javax.transaction.xa,javax.xml,'
                                'javax.xml.catalog,javax.xml.crypto,javax.xml.crypto.dom,javax.xml.crypto.dsig,'
                                'javax.xml.crypto.dsig.dom,javax.xml.crypto.dsig.keyinfo,javax.xml.crypto.dsig.spec,'
                                'javax.xml.datatype,javax.xml.namespace,javax.xml.parsers,javax.xml.stream,'
                                'javax.xml.stream.events,javax.xml.stream.util,javax.xml.transform,'
                                'javax.xml.transform.dom,javax.xml.transform.sax,javax.xml.transform.stax,'
                                'javax.xml.transform.stream,javax.xml.validation,javax.xml.xpath,jdk.dynalink,'
                                'jdk.dynalink.beans,jdk.dynalink.internal,jdk.dynalink.linker,'
                                'jdk.dynalink.linker.support,jdk.dynalink.support,jdk.editpad,jdk.editpad.resources,'
                                'jdk.internal,jdk.internal.agent,jdk.internal.agent.resources,jdk.internal.agent.spi,'
                                'jdk.internal.editor.external,jdk.internal.editor.spi,jdk.internal.event,'
                                'jdk.internal.jimage,jdk.internal.jimage.decompressor,jdk.internal.jmod,'
                                'jdk.internal.joptsimple,jdk.internal.joptsimple.internal,'
                                'jdk.internal.joptsimple.util,jdk.internal.jrtfs,jdk.internal.jshell.debug,'
                                'jdk.internal.jshell.tool,jdk.internal.jshell.tool.resources,jdk.internal.loader,'
                                'jdk.internal.logger,jdk.internal.math,jdk.internal.misc,jdk.internal.module,'
                                'jdk.internal.net.http,jdk.internal.net.http.common,jdk.internal.net.http.frame,'
                                'jdk.internal.net.http.hpack,jdk.internal.net.http.websocket,'
                                'jdk.internal.netscape.javascript.spi,jdk.internal.org.jline.keymap,'
                                'jdk.internal.org.jline.reader,jdk.internal.org.jline.reader.impl,'
                                'jdk.internal.org.jline.reader.impl.completer,'
                                'jdk.internal.org.jline.reader.impl.history,jdk.internal.org.jline.terminal,'
                                'jdk.internal.org.jline.terminal.impl,jdk.internal.org.jline.terminal.spi,'
                                'jdk.internal.org.jline.utils,jdk.internal.org.objectweb.asm,'
                                'jdk.internal.org.objectweb.asm.commons,jdk.internal.org.objectweb.asm.signature,'
                                'jdk.internal.org.objectweb.asm.tree,jdk.internal.org.objectweb.asm.tree.analysis,'
                                'jdk.internal.org.objectweb.asm.util,jdk.internal.org.xml.sax,'
                                'jdk.internal.org.xml.sax.helpers,jdk.internal.perf,jdk.internal.platform,'
                                'jdk.internal.platform.cgroupv1,jdk.internal.ref,jdk.internal.reflect,'
                                'jdk.internal.shellsupport.doc,jdk.internal.shellsupport.doc.resources,'
                                'jdk.internal.util,jdk.internal.util.jar,jdk.internal.util.xml,'
                                'jdk.internal.util.xml.impl,jdk.internal.vm,jdk.internal.vm.annotation,'
                                'jdk.javadoc.doclet,jdk.javadoc.internal.api,'
                                'jdk.javadoc.internal.doclets.formats.html,'
                                'jdk.javadoc.internal.doclets.formats.html.markup,'
                                'jdk.javadoc.internal.doclets.formats.html.resources,'
                                'jdk.javadoc.internal.doclets.formats.html.resources.jquery,'
                                'jdk.javadoc.internal.doclets.formats.html.resources.jquery.external.jquery,'
                                'jdk.javadoc.internal.doclets.formats.html.resources.jquery.images,'
                                'jdk.javadoc.internal.doclets.formats.html.resources.jquery.jszip.dist,'
                                'jdk.javadoc.internal.doclets.toolkit,jdk.javadoc.internal.doclets.toolkit.builders,'
                                'jdk.javadoc.internal.doclets.toolkit.resources,'
                                'jdk.javadoc.internal.doclets.toolkit.taglets,'
                                'jdk.javadoc.internal.doclets.toolkit.util,'
                                'jdk.javadoc.internal.doclets.toolkit.util.links,jdk.javadoc.internal.tool,'
                                'jdk.javadoc.internal.tool.resources,jdk.jfr,jdk.jfr.consumer,jdk.jfr.events,'
                                'jdk.jfr.internal,jdk.jfr.internal.consumer,jdk.jfr.internal.dcmd,'
                                'jdk.jfr.internal.handlers,jdk.jfr.internal.instrument,jdk.jfr.internal.jfc,'
                                'jdk.jfr.internal.management,jdk.jfr.internal.settings,jdk.jfr.internal.test,'
                                'jdk.jfr.internal.tool,jdk.jfr.internal.types,jdk.jshell,jdk.jshell.execution,'
                                'jdk.jshell.resources,jdk.jshell.spi,jdk.jshell.tool,jdk.jshell.tool.resources,'
                                'jdk.management.jfr,jdk.management.jfr.internal,jdk.net,jdk.nio,jdk.nio.zipfs,'
                                'jdk.security.jarsigner,jdk.swing.interop,jdk.swing.interop.internal,'
                                'jdk.tools.jimage,jdk.tools.jimage.resources,jdk.tools.jlink.builder,'
                                'jdk.tools.jlink.internal,jdk.tools.jlink.internal.packager,'
                                'jdk.tools.jlink.internal.plugins,jdk.tools.jlink.plugin,jdk.tools.jlink.resources,'
                                'jdk.tools.jmod,jdk.tools.jmod.resources,jdk.vm.ci.aarch64,jdk.vm.ci.amd64,'
                                'jdk.vm.ci.code,jdk.vm.ci.code.site,jdk.vm.ci.code.stack,jdk.vm.ci.common,'
                                'jdk.vm.ci.hotspot,jdk.vm.ci.hotspot.aarch64,jdk.vm.ci.hotspot.amd64,'
                                'jdk.vm.ci.hotspot.sparc,jdk.vm.ci.meta,jdk.vm.ci.runtime,jdk.vm.ci.services,'
                                'jdk.vm.ci.sparc,jdk.xml.internal,org.ietf.jgss,org.jcp.xml.dsig.internal,'
                                'org.jcp.xml.dsig.internal.dom,org.w3c.dom,org.w3c.dom.bootstrap,org.w3c.dom.css,'
                                'org.w3c.dom.events,org.w3c.dom.html,org.w3c.dom.ls,org.w3c.dom.ranges,'
                                'org.w3c.dom.stylesheets,org.w3c.dom.traversal,org.w3c.dom.views,org.w3c.dom.xpath,'
                                'org.xml.sax,org.xml.sax.ext,org.xml.sax.helpers,sun.applet,sun.awt,sun.awt.X11,'
                                'sun.awt.datatransfer,sun.awt.dnd,sun.awt.event,sun.awt.geom,sun.awt.im,'
                                'sun.awt.image,sun.awt.resources,sun.awt.resources.cursors,sun.awt.shell,'
                                'sun.awt.util,sun.awt.www.content,sun.awt.www.content.audio,'
                                'sun.awt.www.content.image,sun.datatransfer,sun.datatransfer.resources,sun.font,'
                                'sun.font.lookup,sun.instrument,sun.invoke,sun.invoke.empty,sun.invoke.util,'
                                'sun.java2d,sun.java2d.cmm,sun.java2d.cmm.lcms,sun.java2d.cmm.profiles,'
                                'sun.java2d.loops,sun.java2d.marlin,sun.java2d.marlin.stats,sun.java2d.opengl,'
                                'sun.java2d.pipe,sun.java2d.pipe.hw,sun.java2d.x11,sun.java2d.xr,sun.jvmstat,'
                                'sun.jvmstat.monitor,sun.jvmstat.monitor.event,sun.jvmstat.monitor.remote,'
                                'sun.jvmstat.perfdata.monitor,sun.jvmstat.perfdata.monitor.protocol.file,'
                                'sun.jvmstat.perfdata.monitor.protocol.local,'
                                'sun.jvmstat.perfdata.monitor.protocol.rmi,sun.jvmstat.perfdata.monitor.v1_0,'
                                'sun.jvmstat.perfdata.monitor.v2_0,sun.jvmstat.perfdata.resources,sun.launcher,'
                                'sun.launcher.resources,sun.management,sun.management.counter,'
                                'sun.management.counter.perf,sun.management.jdp,sun.management.jmxremote,'
                                'sun.management.spi,sun.misc,sun.net,sun.net.dns,sun.net.ext,sun.net.ftp,'
                                'sun.net.ftp.impl,sun.net.httpserver,sun.net.idn,sun.net.sdp,sun.net.smtp,'
                                'sun.net.spi,sun.net.util,sun.net.www,sun.net.www.content.text,sun.net.www.http,'
                                'sun.net.www.protocol.file,sun.net.www.protocol.ftp,sun.net.www.protocol.http,'
                                'sun.net.www.protocol.http.logging,sun.net.www.protocol.http.ntlm,'
                                'sun.net.www.protocol.http.spnego,sun.net.www.protocol.https,'
                                'sun.net.www.protocol.jar,sun.net.www.protocol.jmod,sun.net.www.protocol.jrt,'
                                'sun.net.www.protocol.mailto,sun.nio,sun.nio.ch,sun.nio.ch.sctp,sun.nio.cs,'
                                'sun.nio.cs.ext,sun.nio.fs,sun.print,sun.print.resources,sun.reflect,'
                                'sun.reflect.annotation,sun.reflect.generics.factory,sun.reflect.generics.parser,'
                                'sun.reflect.generics.reflectiveObjects,sun.reflect.generics.repository,'
                                'sun.reflect.generics.scope,sun.reflect.generics.tree,sun.reflect.generics.visitor,'
                                'sun.reflect.misc,sun.rmi.log,sun.rmi.registry,sun.rmi.registry.resources,'
                                'sun.rmi.runtime,sun.rmi.server,sun.rmi.server.resources,sun.rmi.transport,'
                                'sun.rmi.transport.tcp,sun.security.action,sun.security.ec,sun.security.ec.point,'
                                'sun.security.internal.interfaces,sun.security.internal.spec,sun.security.jca,'
                                'sun.security.jgss,sun.security.jgss.krb5,sun.security.jgss.spi,'
                                'sun.security.jgss.spnego,sun.security.jgss.wrapper,sun.security.krb5,'
                                'sun.security.krb5.internal,sun.security.krb5.internal.ccache,'
                                'sun.security.krb5.internal.crypto,sun.security.krb5.internal.crypto.dk,'
                                'sun.security.krb5.internal.ktab,sun.security.krb5.internal.rcache,'
                                'sun.security.krb5.internal.util,sun.security.pkcs,sun.security.pkcs10,'
                                'sun.security.pkcs11,sun.security.pkcs11.wrapper,sun.security.pkcs12,'
                                'sun.security.provider,sun.security.provider.certpath,'
                                'sun.security.provider.certpath.ldap,sun.security.provider.certpath.ssl,'
                                'sun.security.rsa,sun.security.smartcardio,sun.security.ssl,sun.security.timestamp,'
                                'sun.security.tools,sun.security.tools.jarsigner,sun.security.tools.keytool,'
                                'sun.security.util,sun.security.util.math,sun.security.util.math.intpoly,'
                                'sun.security.validator,sun.security.x509,sun.swing,sun.swing.icon,sun.swing.plaf,'
                                'sun.swing.plaf.synth,sun.swing.table,sun.swing.text,sun.swing.text.html,sun.text,'
                                'sun.text.bidi,sun.text.normalizer,sun.text.resources,sun.text.resources.cldr,'
                                'sun.text.resources.cldr.ext,sun.text.resources.ext,sun.text.spi,sun.tools.attach,'
                                'sun.tools.jar,sun.tools.jar.resources,sun.tools.jconsole,'
                                'sun.tools.jconsole.inspector,sun.tools.jconsole.resources,sun.tools.jstatd,'
                                'sun.tools.serialver,sun.tools.serialver.resources,sun.util,sun.util.calendar,'
                                'sun.util.cldr,sun.util.locale,sun.util.locale.provider,sun.util.logging,'
                                'sun.util.logging.internal,sun.util.logging.resources,sun.util.resources,'
                                'sun.util.resources.cldr,sun.util.resources.cldr.ext,'
                                'sun.util.resources.cldr.provider,sun.util.resources.ext,sun.util.resources.provider,'
                                'sun.util.sp']

        with stages.set_command(config.base_image_build_args + executable_name_args + instrument_args) as s:
            s.execute_command()
            if s.exit_code == 0:
                mx.copyfile(image_path, image_path_latest)
            if i + 1 == instrumented_iterations and s.exit_code == 0:
                image_size = os.stat(image_path).st_size
                out('Instrumented image size: ' + str(image_size) + ' B')

    def run_stage_instrument_run(self, config, stages, image_path, profile_path):
        image_run_cmd = [image_path, '-XX:ProfilesDumpFile=' + profile_path]
        image_run_cmd += config.extra_profile_run_args
        with stages.set_command(image_run_cmd) as s:
            s.execute_command()
            if s.exit_code == 0:
                mx.copyfile(profile_path, config.latest_profile_path)

    def run_stage_image(self, config, stages):
        executable_name_args = ['-H:Name=' + config.final_image_name]
        pgo_verification_output_path = os.path.join(config.output_dir, config.final_image_name + '-probabilities.log')
        pgo_args = ['--pgo=' + config.latest_profile_path, '-H:+VerifyPGOProfiles', '-H:VerificationDumpFile=' + pgo_verification_output_path]
        pgo_args += ['-H:' + ('+' if self.pgo_context_sensitive else '-') + 'EnablePGOContextSensitivity']
        pgo_args += ['-H:+AOTInliner'] if self.pgo_aot_inline else ['-H:-AOTInliner']

        jdk_cache_args = []
        if self.use_jdk_cache:
            jdk_cache_path = os.path.join(os.environ['HOME'], '.graal_ml/jdk_cahe/')
            if not os.path.isdir(jdk_cache_path):
                mx.abort("Fatal Error: invalid jdk cache path: {}.".format(jdk_cache_path))
            jdk_cache_command = list(map(lambda f: os.path.join(jdk_cache_path, f), filter(lambda f: f.endswith(config.profile_file_extension), os.listdir(jdk_cache_path))))
            jdk_cache_args += ['-H:DefaultProfilesUse={}'.format(','.join(jdk_cache_command))]

        if self.mlpgo_model == 'tree':
            mlpgo_args = ['-H:+MLPGOTree']
        elif self.mlpgo_model == 'knn':
            mlpgo_args = ['-H:+MLPGOKnn']
        elif self.mlpgo_model == 'dnn':
            mlpgo_args = ['-H:+MLPGODnn', '-J-Djava.library.path='+os.environ['HOME']+'/.graal_ml/config_dnn/libtensorflow_jni_path/']
        elif self.mlpgo_model == 'mi-dnn':
            mlpgo_args = ['-H:+MLPGOMiDnn', '-J-Djava.library.path='+os.environ['HOME']+'/.graal_ml/config_mdnn/libtensorflow_jni_path/']
        else:
            mlpgo_args = []
        if self.mlpgo_polite:
            mlpgo_args += ['-H:+MLPGOPolite']
        if self.mlpgo_multi_branch:
            mlpgo_args += ['-H:+MLPGOMultiBranch']

        final_image_command = config.base_image_build_args + executable_name_args + (pgo_args if self.pgo_instrumented_iterations > 0 or (self.hotspot_pgo and os.path.exists(config.latest_profile_path)) else []) + jdk_cache_args + mlpgo_args
        with stages.set_command(final_image_command) as s:
            s.execute_command()

    def run_stage_run(self, config, stages, out):
        image_path = os.path.join(config.output_dir, config.final_image_name)
        with stages.set_command([image_path] + config.image_run_args + ['-XX:MaxHeapSize=24G', '-Xmn4G']) as s:
            s.execute_command(vm=self)
            if s.exit_code == 0:
                # The image size for benchmarks is tracked by printing on stdout and matching the rule.
                image_size = os.stat(image_path).st_size
                available_benchmark_name = config.executable_name if (config.benchmark_suite_name is None) or (config.benchmark_name is None) else config.benchmark_suite_name + ':' + config.benchmark_name
                out('The executed image size for benchmark ' + available_benchmark_name + ' is ' + str(image_size) + ' B')
                image_sections_command = "objdump -h " + image_path
                out(subprocess.check_output(image_sections_command, shell=True, universal_newlines=True))
                for config_type in ['jni', 'proxy', 'predefined-classes', 'reflect', 'resource', 'serialization']:
                    config_path = os.path.join(config.config_dir, config_type + '-config.json')
                    if os.path.exists(config_path):
                        config_size = os.stat(config_path).st_size
                        out('The ' + config_type + ' configuration size for benchmark ' + config.benchmark_suite_name + ':' + config.benchmark_name + ' is ' + str(config_size) + ' B')

    def run_java(self, args, out=None, err=None, cwd=None, nonZeroIsFatal=False):

        if '-version' in args:
            return super(NativeImageVM, self).run_java(args, out=out, err=err, cwd=cwd, nonZeroIsFatal=nonZeroIsFatal)

        if self.bmSuite is None:
            mx.abort("Benchmark suite was not registed.")

        if not callable(getattr(self.bmSuite, "run_stage", None)):
            mx.abort("Benchmark suite is not a NativeImageMixin.")

        # never fatal, we handle it ourselves
        config = NativeImageVM.BenchmarkConfig(self, self.bmSuite, args)
        stages = NativeImageVM.Stages(config, out, err, self.is_gate, True if self.is_gate else nonZeroIsFatal, os.path.abspath(cwd if cwd else os.getcwd()))
        instrumented_iterations = self.pgo_instrumented_iterations if config.pgo_iteration_num is None else int(config.pgo_iteration_num)

        if not os.path.exists(config.output_dir):
            os.makedirs(config.output_dir)

        if not os.path.exists(config.config_dir):
            os.makedirs(config.config_dir)

        if stages.change_stage('agent'):
            if instrumented_iterations == 0 and config.last_stage.startswith('instrument-'):
                config.last_stage = 'agent'
            self.run_stage_agent(config, stages)

        if not self.hotspot_pgo:
            # Native Image profile collection
            for i in range(instrumented_iterations):
                profile_path = config.profile_path_no_extension + '-' + str(i) + config.profile_file_extension
                instrumentation_image_name = config.executable_name + '-instrument-' + str(i)
                instrumentation_image_latest = config.executable_name + '-instrument-latest'

                image_path = os.path.join(config.output_dir, instrumentation_image_name)
                image_path_latest = os.path.join(config.output_dir, instrumentation_image_latest)
                if stages.change_stage('instrument-image', str(i)):
                    self.run_stage_instrument_image(config, stages, out, i, instrumentation_image_name, image_path, image_path_latest, instrumented_iterations)

                if stages.change_stage('instrument-run', str(i)):
                    self.run_stage_instrument_run(config, stages, image_path, profile_path)

        # Build the final image
        if stages.change_stage('image'):
            self.run_stage_image(config, stages)

        # Execute the benchmark
        if stages.change_stage('run'):
            self.run_stage_run(config, stages, out)

    def create_log_files(self, config, executable_name, stage):
        stdout_path = os.path.abspath(
            os.path.join(config.log_dir, executable_name + '-' + stage.current_stage + '-stdout.log'))
        stderr_path = os.path.abspath(
            os.path.join(config.log_dir, executable_name + '-' + stage.current_stage + '-stderr.log'))
        return stderr_path, stdout_path


class NativeImageBuildVm(GraalVm):
    def run(self, cwd, args):
        return self.run_launcher('native-image', args, cwd)


class GuVm(GraalVm):
    def run(self, cwd, args):
        return self.run_launcher('gu', ['rebuild-images'] + args, cwd)


class NativeImageBuildBenchmarkSuite(mx_benchmark.VmBenchmarkSuite):
    def __init__(self, name, benchmarks, registry):
        super(NativeImageBuildBenchmarkSuite, self).__init__()
        self._name = name
        self._benchmarks = benchmarks
        self._registry = registry

    def group(self):
        return 'Graal'

    def subgroup(self):
        return 'substratevm'

    def name(self):
        return self._name

    def benchmarkList(self, bmSuiteArgs):
        return list(self._benchmarks.keys())

    def createVmCommandLineArgs(self, benchmarks, runArgs):
        if not benchmarks:
            benchmarks = self.benchmarkList(runArgs)

        cmd_line_args = []
        for bench in benchmarks:
            cmd_line_args += self._benchmarks[bench]
        return cmd_line_args + runArgs

    def get_vm_registry(self):
        return self._registry

    def rules(self, output, benchmarks, bmSuiteArgs):
        class NativeImageTimeToInt(object):
            def __call__(self, *args, **kwargs):
                return int(float(args[0].replace(',', '')))

        return [
            mx_benchmark.StdOutRule(r'^\[(?P<benchmark>\S+?):[0-9]+\][ ]+\[total\]:[ ]+(?P<time>[0-9,.]+?) ms', {
                "bench-suite": self.name(),
                "benchmark": ("<benchmark>", str),
                "metric.name": "time",
                "metric.type": "numeric",
                "metric.unit": "ms",
                "metric.value": ("<time>", NativeImageTimeToInt()),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": 0,
            })
        ]


class AgentScriptJsBenchmarkSuite(mx_benchmark.VmBenchmarkSuite):
    def __init__(self):
        super(AgentScriptJsBenchmarkSuite, self).__init__()
        self._benchmarks = {
            'plain' : [],
            'triple' : ['--insight=sieve-filter1.js', '--experimental-options'],
            'single' : ['--insight=sieve-filter2.js', '--experimental-options'],
        }

    def group(self):
        return 'Graal'

    def subgroup(self):
        return 'graal-js'

    def name(self):
        return 'agentscript'

    def version(self):
        return '0.1.0'

    def benchmarkList(self, bmSuiteArgs):
        return self._benchmarks.keys()

    def failurePatterns(self):
        return [
            re.compile(r'error:'),
            re.compile(r'internal error:'),
            re.compile(r'Error in'),
            re.compile(r'\tat '),
            re.compile(r'Defaulting the .*\. Consider using '),
            re.compile(r'java.lang.OutOfMemoryError'),
        ]

    def successPatterns(self):
        return [
            re.compile(r'Hundred thousand prime numbers in [0-9]+ ms', re.MULTILINE),
        ]

    def rules(self, out, benchmarks, bmSuiteArgs):
        assert len(benchmarks) == 1
        return [
            mx_benchmark.StdOutRule(r'^Hundred thousand prime numbers in (?P<time>[0-9]+) ms\n$', {
                "bench-suite": self.name(),
                "benchmark": (benchmarks[0], str),
                "metric.name": "time",
                "metric.type": "numeric",
                "metric.unit": "ms",
                "metric.value": ("<time>", int),
                "metric.score-function": "id",
                "metric.better": "lower",
                "metric.iteration": ("$iteration", int),
            })
        ]

    def createCommandLineArgs(self, benchmarks, bmSuiteArgs):
        return self.vmArgs(bmSuiteArgs) + super(AgentScriptJsBenchmarkSuite, self).createCommandLineArgs(benchmarks, bmSuiteArgs)

    def workingDirectory(self, benchmarks, bmSuiteArgs):
        return join(_suite.dir, 'benchmarks', 'agentscript')

    def createVmCommandLineArgs(self, benchmarks, runArgs):
        if not benchmarks:
            raise mx.abort("Benchmark suite '{}' cannot run multiple benchmarks in the same VM process".format(self.name()))
        if len(benchmarks) != 1:
            raise mx.abort("Benchmark suite '{}' can run only one benchmark at a time".format(self.name()))
        return self._benchmarks[benchmarks[0]] + ['-e', 'count=50'] + runArgs + ['sieve.js']

    def get_vm_registry(self):
        return mx_benchmark.js_vm_registry


class PolyBenchBenchmarkSuite(mx_benchmark.VmBenchmarkSuite):
    def __init__(self):
        super(PolyBenchBenchmarkSuite, self).__init__()
        self._extensions = [".js", ".rb", ".wasm", ".bc", ".py", ".jar"]

    def _get_benchmark_root(self):
        if not hasattr(self, '_benchmark_root'):
            dist_name = "POLYBENCH_BENCHMARKS"
            distribution = mx.distribution(dist_name)
            _root = distribution.get_output()
            if not os.path.exists(_root):
                msg = "The distribution {} does not exist: {}{}".format(dist_name, _root, os.linesep)
                msg += "This might be solved by running: mx build --dependencies={}".format(dist_name)
                mx.abort(msg)
            self._benchmark_root = _root
        return self._benchmark_root

    def group(self):
        return "Graal"

    def subgroup(self):
        return "truffle"

    def name(self):
        return "polybench"

    def version(self):
        return "0.1.0"

    def benchmarkList(self, bmSuiteArgs):
        if not hasattr(self, "_benchmarks"):
            self._benchmarks = []
            for group in ["interpreter", "compiler", "warmup"]:
                dir_path = os.path.join(self._get_benchmark_root(), group)
                for f in os.listdir(dir_path):
                    f_path = os.path.join(dir_path, f)
                    if os.path.isfile(f_path) and os.path.splitext(f_path)[1] in self._extensions:
                        self._benchmarks.append(os.path.join(group, f))
        return self._benchmarks

    def createCommandLineArgs(self, benchmarks, bmSuiteArgs):
        if benchmarks is None or len(benchmarks) != 1:
            mx.abort("Must specify one benchmark at a time.")
        vmArgs = self.vmArgs(bmSuiteArgs)
        benchmark_path = os.path.join(self._get_benchmark_root(), benchmarks[0])
        return ["--path=" + benchmark_path] + vmArgs

    def get_vm_registry(self):
        return _polybench_vm_registry

    def rules(self, output, benchmarks, bmSuiteArgs):
        metric_name = self._get_metric_name(bmSuiteArgs)
        return [
            mx_benchmark.StdOutRule(r"\[(?P<name>.*)\] after run: (?P<value>.*) (?P<unit>.*)", {
                "benchmark": ("<name>", str),
                "metric.better": "lower",
                "metric.name": metric_name,
                "metric.unit": ("<unit>", str),
                "metric.value": ("<value>", float),
                "metric.type": "numeric",
                "metric.score-function": "id",
                "metric.iteration": 0,
            })
        ]

    def _get_metric_name(self, bmSuiteArgs):
        metric = None
        for arg in bmSuiteArgs:
            if arg.startswith("--metric="):
                metric = arg[len("--metric="):]
                break
        if metric == "compilation-time":
            return "compile-time"
        elif metric == "partial-evaluation-time":
            return "pe-time"
        elif metric == "one-shot":
            return "one-shot"
        else:
            return "time"

class PolyBenchVm(GraalVm):
    def run(self, cwd, args):
        return self.run_launcher('polybench', args, cwd)


mx_benchmark.add_bm_suite(NativeImageBuildBenchmarkSuite(name='native-image', benchmarks={'js': ['--language:js']}, registry=_native_image_vm_registry))
mx_benchmark.add_bm_suite(NativeImageBuildBenchmarkSuite(name='gu', benchmarks={'js': ['js'], 'libpolyglot': ['libpolyglot']}, registry=_gu_vm_registry))
mx_benchmark.add_bm_suite(AgentScriptJsBenchmarkSuite())
mx_benchmark.add_bm_suite(PolyBenchBenchmarkSuite())

def register_graalvm_vms():
    default_host_vm_name = mx_sdk_vm_impl.graalvm_dist_name().lower().replace('_', '-')
    host_vm_names = ([default_host_vm_name.replace('-java8', '')] if '-java8' in default_host_vm_name else []) + [default_host_vm_name]
    for host_vm_name in host_vm_names:
        for config_name, java_args, launcher_args, priority in mx_sdk_vm.get_graalvm_hostvm_configs():
            mx_benchmark.java_vm_registry.add_vm(GraalVm(host_vm_name, config_name, java_args, launcher_args), _suite, priority)
            for mode, mode_options in _polybench_modes:
                _polybench_vm_registry.add_vm(PolyBenchVm(host_vm_name, config_name + "-" + mode, [], mode_options + launcher_args))
        if mx_sdk_vm_impl.has_component('svm'):
            _native_image_vm_registry.add_vm(NativeImageBuildVm(host_vm_name, 'default', [], []), _suite, 10)
            _gu_vm_registry.add_vm(GuVm(host_vm_name, 'default', [], []), _suite, 10)

    # We support only EE and CE configuration for native-image benchmarks
    for short_name, config_suffix in [('niee', 'ee'), ('ni', 'ce')]:
        if any(component.short_name == short_name for component in mx_sdk_vm_impl.registered_graalvm_components(stage1=False)):
            mx_benchmark.add_java_vm(NativeImageVM('native-image', 'default-' + config_suffix), _suite, 10)
            mx_benchmark.add_java_vm(NativeImageVM('native-image', 'gate-' + config_suffix, is_gate=True), _suite, 10)
            mx_benchmark.add_java_vm(NativeImageVM('native-image', 'llvm-' + config_suffix, is_llvm=True), _suite, 10)
            mx_benchmark.add_java_vm(NativeImageVM('native-image', 'native-architecture-' + config_suffix, native_architecture=True), _suite, 10)
            break

    # Add VMs for libgraal
    if mx_sdk_vm_impl.has_component('LibGraal'):
        libgraal_location = mx_sdk_vm_impl.get_native_image_locations('LibGraal', 'jvmcicompiler')
        if libgraal_location is not None:
            import mx_graal_benchmark
            mx_graal_benchmark.build_jvmci_vm_variants('server', 'graal-core-libgraal',
                                                       ['-server', '-XX:+EnableJVMCI', '-Dgraal.CompilerConfiguration=community', '-Djvmci.Compiler=graal', '-XX:+UseJVMCINativeLibrary', '-XX:JVMCILibPath=' + dirname(libgraal_location)],
                                                       mx_graal_benchmark._graal_variants, suite=_suite, priority=15, hosted=False)
