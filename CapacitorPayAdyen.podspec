require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorPayAdyen'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.source_files = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '14.0'
  s.dependency 'Capacitor'
  # NOTE: verify this dependency name/version. Adyen's iOS SDK (https://github.com/Adyen/adyen-ios)
  # is primarily distributed via Swift Package Manager; Tap to Pay may not be available as a
  # CocoaPods subspec under this name. If `pod install` can't find it, add the SPM package
  # directly to the consuming app's Xcode project instead and remove this dependency line.
  s.dependency 'Adyen/TapToPay', '~> 5.0'
  s.swift_version = '5.1'
end
